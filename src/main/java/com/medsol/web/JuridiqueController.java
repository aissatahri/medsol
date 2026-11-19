package com.medsol.web;

import com.medsol.abonnement.Editeur;
import com.medsol.audit.AuditService;
import com.medsol.cabinet.RegistrationService;
import com.medsol.security.MedsolUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Documents juridiques publics (accord de traitement des données, confidentialité, mentions légales) et acceptation
 * par le médecin titulaire : à l'inscription, ou à sa connexion suivante pour un cabinet existant ou après une nouvelle
 * version. Tant qu'il n'a pas accepté, le médecin est conduit à la page d'acceptation (l'assistante n'est pas bloquée).
 */
@Controller
public class JuridiqueController {

    static final String ACCEPTES = "medsol.documentsAcceptes";

    private final Editeur editeur;
    private final RegistrationService registration;
    private final AuditService audit;

    public JuridiqueController(Editeur editeur, RegistrationService registration, AuditService audit) {
        this.editeur = editeur;
        this.registration = registration;
        this.audit = audit;
    }

    private void editeur(Model model) {
        model.addAttribute("version", RegistrationService.CGU_VERSION);
        model.addAttribute("editeur", editeur.tous());
        model.addAttribute("editeurNom", editeur.raisonSociale());
    }

    @GetMapping("/sous-traitance")
    public String sousTraitance(Model model) { editeur(model); return "juridique/sous-traitance"; }

    @GetMapping("/confidentialite")
    public String confidentialite(Model model) { editeur(model); return "juridique/confidentialite"; }

    @GetMapping("/mentions-legales")
    public String mentions(Model model) { editeur(model); return "juridique/mentions-legales"; }

    @GetMapping("/accepter-conditions")
    public String accepterPage(Model model) { editeur(model); return "juridique/accepter"; }

    @PostMapping("/accepter-conditions")
    public String accepter(@RequestParam(defaultValue = "false") boolean accepte, @AuthenticationPrincipal MedsolUser user,
                           HttpSession session, Model model) {
        if (!user.medecin()) return "redirect:/";
        if (!accepte) {
            editeur(model);
            model.addAttribute("refus", true);
            return "juridique/accepter";
        }
        registration.accepter(user.cabinetId(), user.id());
        audit.log("CONDITIONS_ACCEPTEES", "cabinet", RegistrationService.CGU_VERSION);
        session.setAttribute(ACCEPTES, RegistrationService.CGU_VERSION);
        return "redirect:/";
    }

    /** Médecin d'un cabinet qui n'a pas accepté la version courante : page d'acceptation avant le reste. */
    static class Controle implements HandlerInterceptor {

        private static final List<String> LIBRES = List.of("/accepter-conditions", "/conditions", "/sous-traitance", "/confidentialite",
                "/mentions-legales", "/logout", "/lang", "/compte", "/2fa", "/error", "/css/", "/js/", "/img/", "/plateforme");

        private final RegistrationService registration;

        Controle(RegistrationService registration) { this.registration = registration; }

        @Override
        public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof MedsolUser u) || !u.medecin() || u.plateforme()) return true;
            String chemin = req.getRequestURI().substring(req.getContextPath().length());
            if (LIBRES.stream().anyMatch(chemin::startsWith)) return true;
            HttpSession session = req.getSession(false);
            if (session != null && RegistrationService.CGU_VERSION.equals(session.getAttribute(ACCEPTES))) return true;
            if (registration.documentsAcceptes()) {
                if (session != null) session.setAttribute(ACCEPTES, RegistrationService.CGU_VERSION);
                return true;
            }
            String cible = req.getContextPath() + "/accepter-conditions";
            if (req.getHeader("HX-Request") != null) {
                res.setHeader("HX-Redirect", cible);
            } else {
                res.sendRedirect(cible);
            }
            return false;
        }
    }

    @Configuration
    static class Enregistrement implements WebMvcConfigurer {
        private final RegistrationService registration;

        Enregistrement(RegistrationService registration) { this.registration = registration; }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new Controle(registration));
        }
    }
}
