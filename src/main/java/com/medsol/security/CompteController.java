package com.medsol.security;

import com.medsol.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * « Mon compte » (tous les rôles) : nom affiché et langue, changement de son mot de passe (avec l'ancien : les autres
 * appareils sont déconnectés), double authentification par application (Google Authenticator, Microsoft
 * Authenticator...). Puis l'étape du code à la connexion ({@code /2fa}).
 */
@Controller
public class CompteController {

    private static final String SECRET_EN_COURS = "medsol.2fa.secret";
    private static final String ECHECS = "medsol.2fa.echecs";
    private static final java.util.Collection<String> LANGUES = com.medsol.web.Langues.SUPPORTEES;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final LocaleResolver locales;
    private final boolean exige2fa;
    private final DoubleAuthentification deuxFacteurs;

    public CompteController(JdbcTemplate jdbc, PasswordEncoder encoder, AuditService audit, LocaleResolver locales,
                            @Value("${medsol.security.require-2fa-doctors:false}") boolean exige2fa, DoubleAuthentification deuxFacteurs) {
        this.deuxFacteurs = deuxFacteurs;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.locales = locales;
        this.exige2fa = exige2fa;
    }

    @GetMapping("/compte")
    public String page(@AuthenticationPrincipal MedsolUser me, HttpSession session, Model model) {
        model.addAttribute("langue", jdbc.queryForObject("SELECT langue FROM utilisateur WHERE id = ?", String.class, me.id()));
        model.addAttribute("obligatoire", exige2fa && (me.medecin() || me.plateforme()));
        if (me.totp()) model.addAttribute("codesRestants", deuxFacteurs.codesRestants(me.id()));
        String secret = (String) session.getAttribute(SECRET_EN_COURS);
        if (secret != null && !me.totp()) {
            model.addAttribute("secret", secret.replaceAll("(.{4})(?=.)", "$1 "));
            model.addAttribute("qr", Totp.qrSvg(Totp.uri(secret, me.email(), "MedSol")));
        }
        return "compte/page";
    }

    @PostMapping("/compte/profil")
    public String profil(@AuthenticationPrincipal MedsolUser me, @RequestParam String fullName, @RequestParam String langue,
                         HttpServletRequest req, HttpServletResponse res, RedirectAttributes ra) {
        String nom = fullName.strip();
        if (nom.isEmpty() || nom.length() > 150 || !LANGUES.contains(langue)) {
            ra.addFlashAttribute("error", "users.invalid");
            return "redirect:/compte";
        }
        jdbc.update("UPDATE utilisateur SET nom_complet = ?, langue = ? WHERE id = ?", nom, langue, me.id());
        SessionGuard.remplacer(me.avec(nom, me.versionSession(), me.totp(), langue), req, res);
        locales.setLocale(req, res, Locale.forLanguageTag(langue));
        audit.log("COMPTE_PROFIL", "utilisateur", me.id());
        ra.addFlashAttribute("done", "account.saved");
        return "redirect:/compte";
    }

    @PostMapping("/compte/mot-de-passe")
    public String motDePasse(@AuthenticationPrincipal MedsolUser me, @RequestParam String ancien, @RequestParam String password,
                             @RequestParam String confirmation, HttpServletRequest req, HttpServletResponse res, RedirectAttributes ra) {
        String hash = jdbc.queryForObject("SELECT mot_de_passe FROM utilisateur WHERE id = ?", String.class, me.id());
        String erreur = !encoder.matches(ancien, hash) ? "account.password.wrong"
                : password.length() < 10 || password.length() > 100 ? "users.password.short"
                : !password.equals(confirmation) ? "users.password.mismatch"
                : password.equals(ancien) ? "account.password.same" : null;
        if (erreur != null) {
            if ("account.password.wrong".equals(erreur)) audit.log("ECHEC_MOT_DE_PASSE", "utilisateur", me.id());
            ra.addFlashAttribute("error", erreur);
            return "redirect:/compte";
        }
        Integer version = jdbc.queryForObject("UPDATE utilisateur SET mot_de_passe = ?, version_session = version_session + 1 WHERE id = ? RETURNING version_session",
                Integer.class, encoder.encode(password), me.id());
        // cette session reste ouverte ; toutes les autres sont fermées à leur prochaine requête (SessionGuard)
        SessionGuard.remplacer(me.avec(me.fullName(), version, me.totp(), me.langue()), req, res);
        audit.log("COMPTE_MOT_DE_PASSE", "utilisateur", me.id());
        ra.addFlashAttribute("done", "account.password.changed");
        return "redirect:/compte";
    }

    @PostMapping("/compte/2fa/preparer")
    public String preparer(@AuthenticationPrincipal MedsolUser me, HttpSession session) {
        if (!me.totp()) session.setAttribute(SECRET_EN_COURS, Totp.nouveauSecret());
        return "redirect:/compte#deux-facteurs";
    }

    @PostMapping("/compte/2fa/activer")
    public String activer(@AuthenticationPrincipal MedsolUser me, @RequestParam String code, HttpSession session,
                          HttpServletRequest req, HttpServletResponse res, RedirectAttributes ra) {
        String secret = (String) session.getAttribute(SECRET_EN_COURS);
        java.util.OptionalLong pas = Totp.pas(secret, code, Instant.now());
        if (secret == null || pas.isEmpty()) {
            ra.addFlashAttribute("error", "account.2fa.badcode");
            return "redirect:/compte#deux-facteurs";
        }
        // codes de secours montrés une seule fois
        ra.addFlashAttribute("codes", deuxFacteurs.activer(me.id(), secret, pas.getAsLong()));
        session.removeAttribute(SECRET_EN_COURS);
        session.removeAttribute(SessionGuard.ACTIVATION_REQUISE);
        SessionGuard.remplacer(me.avec(me.fullName(), me.versionSession(), true, me.langue()), req, res);
        audit.log("DOUBLE_AUTH_ACTIVEE", "utilisateur", me.id());
        ra.addFlashAttribute("done", "account.2fa.enabled");
        return "redirect:/compte#deux-facteurs";
    }

    @PostMapping("/compte/2fa/desactiver")
    public String desactiver(@AuthenticationPrincipal MedsolUser me, @RequestParam String ancien,
                             HttpServletRequest req, HttpServletResponse res, RedirectAttributes ra) {
        String hash = jdbc.queryForObject("SELECT mot_de_passe FROM utilisateur WHERE id = ?", String.class, me.id());
        if (exige2fa && (me.medecin() || me.plateforme())) {
            ra.addFlashAttribute("error", "account.2fa.required");
        } else if (!encoder.matches(ancien, hash)) {
            ra.addFlashAttribute("error", "account.password.wrong");
        } else {
            deuxFacteurs.retirer(me.id(), false);
            SessionGuard.remplacer(me.avec(me.fullName(), me.versionSession(), false, me.langue()), req, res);
            audit.log("DOUBLE_AUTH_DESACTIVEE", "utilisateur", me.id());
            ra.addFlashAttribute("done", "account.2fa.disabled");
        }
        return "redirect:/compte#deux-facteurs";
    }

    @PostMapping("/compte/2fa/codes")
    public String codes(@AuthenticationPrincipal MedsolUser me, @RequestParam String ancien, RedirectAttributes ra) {
        String hash = jdbc.queryForObject("SELECT mot_de_passe FROM utilisateur WHERE id = ?", String.class, me.id());
        if (!me.totp()) return "redirect:/compte";
        if (!encoder.matches(ancien, hash)) {
            ra.addFlashAttribute("error", "account.password.wrong");
        } else {
            ra.addFlashAttribute("codes", deuxFacteurs.nouveauxCodes(me.id()));
            audit.log("CODES_SECOURS_RENOUVELES", "utilisateur", me.id());
        }
        return "redirect:/compte#deux-facteurs";
    }

    /** Étape du code, après le mot de passe. Cinq codes faux : la session est fermée. */
    @GetMapping("/2fa")
    public String code(HttpSession session) {
        return session.getAttribute(SessionGuard.CODE_ATTENDU) == null ? "redirect:/" : "compte/code";
    }

    @PostMapping("/2fa")
    public String verifier(@AuthenticationPrincipal MedsolUser me, @RequestParam String code, HttpServletRequest req,
                           RedirectAttributes ra) {
        HttpSession session = req.getSession();
        if (session.getAttribute(SessionGuard.CODE_ATTENDU) == null) return "redirect:/";
        DoubleAuthentification.Resultat r = deuxFacteurs.verifier(me.id(), code, Instant.now());
        if (r != DoubleAuthentification.Resultat.REFUS) {
            session.removeAttribute(SessionGuard.CODE_ATTENDU);
            session.removeAttribute(ECHECS);
            req.changeSessionId();
            if (r == DoubleAuthentification.Resultat.SECOURS) {
                // code de secours : on le signale et on montre combien il en reste
                audit.log("CONNEXION_CODE_SECOURS", "utilisateur", me.id());
                ra.addFlashAttribute("info", "account.2fa.backup.used");
                return "redirect:/compte#deux-facteurs";
            }
            audit.log("CONNEXION_2FA", "utilisateur", me.id());
            return "redirect:/";
        }
        audit.log("ECHEC_2FA", "utilisateur", me.id());
        int echecs = session.getAttribute(ECHECS) instanceof Integer n ? n + 1 : 1;
        if (echecs >= 5) {
            session.invalidate();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            return "redirect:/login?error";
        }
        session.setAttribute(ECHECS, echecs);
        return "redirect:/2fa?erreur";
    }
}
