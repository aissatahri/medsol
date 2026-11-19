package com.medsol.security;

import com.medsol.abonnement.AbonnementFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Contrôle de chaque requête d'un compte connecté :
 * <ul>
 *   <li>compte désactivé, ou mot de passe changé depuis l'ouverture de la session ({@code version_session}) :
 *       la session est fermée sur tous les appareils, retour à la connexion ;</li>
 *   <li>droits de l'assistante modifiés par le médecin : appliqués tout de suite, sans la déconnecter ;</li>
 *   <li>double authentification : tant que le code n'est pas saisi, seule la page du code est accessible ; si elle
 *       est obligatoire et pas encore activée, seule la page « Mon compte ».</li>
 * </ul>
 */
public class SessionGuard extends OncePerRequestFilter {

    /** Mot de passe accepté, code de l'application d'authentification attendu. */
    public static final String CODE_ATTENDU = "medsol.2fa.attendu";
    /** Double authentification obligatoire et pas encore activée : seule la page « Mon compte » est ouverte. */
    public static final String ACTIVATION_REQUISE = "medsol.2fa.requise";

    private static final List<String> TOUJOURS = List.of("/logout", "/lang", "/error");

    private final JdbcTemplate jdbc;

    public SessionGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Etat(boolean actif, int version, Set<String> droits) {}

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = AbonnementFilter.chemin(req);
        return p.startsWith("/css/") || p.startsWith("/js/") || p.startsWith("/img/") || p.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof MedsolUser u)) {
            chain.doFilter(req, res);
            return;
        }
        List<Etat> etat = jdbc.query("SELECT actif, version_session, droits FROM utilisateur WHERE id = ?",
                (rs, i) -> new Etat(rs.getBoolean(1), rs.getInt(2), Set.of((String[]) rs.getArray(3).getArray())), u.id());
        if (etat.isEmpty() || !etat.get(0).actif() || etat.get(0).version() != u.versionSession()) {
            HttpSession s = req.getSession(false);
            if (s != null) s.invalidate();
            SecurityContextHolder.clearContext();
            rediriger(req, res, "/login?expire");
            return;
        }
        if (!etat.get(0).droits().equals(u.droits())) {
            remplacer(withDroits(u, etat.get(0).droits()), req, res);
        }
        HttpSession s = req.getSession(false);
        String p = AbonnementFilter.chemin(req);
        boolean libre = TOUJOURS.stream().anyMatch(p::startsWith);
        if (s != null && s.getAttribute(CODE_ATTENDU) != null && !libre && !p.equals("/2fa")) {
            rediriger(req, res, "/2fa");
            return;
        }
        if (s != null && s.getAttribute(ACTIVATION_REQUISE) != null && !libre && !(p.equals("/compte") || p.startsWith("/compte/"))) {
            rediriger(req, res, "/compte?2fa");
            return;
        }
        chain.doFilter(req, res);
    }

    private static void rediriger(HttpServletRequest req, HttpServletResponse res, String cible) throws IOException {
        String url = req.getContextPath() + cible;
        if (req.getHeader("HX-Request") != null) {
            res.setHeader("HX-Redirect", url);
            res.setStatus(HttpServletResponse.SC_OK);
        } else {
            res.sendRedirect(url);
        }
    }

    static MedsolUser withDroits(MedsolUser u, Set<String> droits) {
        return new MedsolUser(u.id(), u.cabinetId(), u.email(), u.passwordHash(), u.fullName(), u.role(), u.enabled(),
                u.lockedUntil(), u.plateforme(), u.versionSession(), u.totp(), Set.copyOf(droits), u.langue());
    }

    /** Remplace le compte de la session (nouveau nom, nouvelle version après un changement de mot de passe...). */
    public static void remplacer(MedsolUser nouveau, HttpServletRequest req, HttpServletResponse res) {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(nouveau, null, nouveau.getAuthorities()));
        SecurityContextHolder.setContext(ctx);
        new HttpSessionSecurityContextRepository().saveContext(ctx, req, res);
    }
}
