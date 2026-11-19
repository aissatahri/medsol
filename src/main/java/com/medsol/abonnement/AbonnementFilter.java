package com.medsol.abonnement;

import com.medsol.cabinet.CabinetService;
import com.medsol.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Abonnement échu ou suspendu : le cabinet passe en lecture seule. Ses données restent consultables (continuité des
 * soins, droit d'accès), mais aucune écriture n'est acceptée en dehors de la page d'abonnement et de la déconnexion.
 * L'état est posé dans la requête pour le bandeau de la mise en page.
 */
public class AbonnementFilter extends OncePerRequestFilter {

    public static final String ATTRIBUT = "abonnementEtat";
    private static final List<String> LIBRES = List.of("/abonnement", "/logout", "/lang", "/plateforme", "/stripe/", "/compte", "/2fa", "/accepter-conditions");

    private final AbonnementService abonnements;
    private final CabinetService cabinets;

    public AbonnementFilter(AbonnementService abonnements, CabinetService cabinets) {
        this.abonnements = abonnements;
        this.cabinets = cabinets;
    }

    /** Chemin dans l'application (sans le contexte), quel que soit le découpage servlet. */
    public static String chemin(HttpServletRequest req) {
        return req.getRequestURI().substring(req.getContextPath().length());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = chemin(req);
        return p.startsWith("/css/") || p.startsWith("/js/") || p.startsWith("/img/") || p.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (TenantContext.get() == null) {
            chain.doFilter(req, res);
            return;
        }
        Optional<AbonnementService.Etat> etat = abonnements.etat(cabinets.current().today());
        etat.ifPresent(e -> req.setAttribute(ATTRIBUT, e));
        boolean ecriture = !("GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod()));
        String chemin = chemin(req);
        if (ecriture && etat.map(AbonnementService.Etat::lectureSeule).orElse(false) && LIBRES.stream().noneMatch(chemin::startsWith)) {
            String cible = req.getContextPath() + "/abonnement?lecture";
            if (req.getHeader("HX-Request") != null) {
                res.setHeader("HX-Redirect", cible);
                res.setStatus(HttpServletResponse.SC_OK);
            } else {
                res.sendRedirect(cible);
            }
            return;
        }
        chain.doFilter(req, res);
    }
}
