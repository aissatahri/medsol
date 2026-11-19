package com.medsol.web;

import com.medsol.cabinet.RegistrationForm;
import com.medsol.cabinet.RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Controller
public class AuthController {

    private static final java.util.Collection<String> LANGUAGES = Langues.SUPPORTEES;

    private final RegistrationService registration;
    private final JdbcTemplate jdbc;
    private final LocaleResolver localeResolver;
    private final com.medsol.abonnement.Editeur editeur;
    private final com.medsol.abonnement.Croissance croissance;

    public AuthController(RegistrationService registration, JdbcTemplate jdbc, LocaleResolver localeResolver,
                          com.medsol.abonnement.Editeur editeur, com.medsol.abonnement.Croissance croissance) {
        this.editeur = editeur;
        this.croissance = croissance;
        this.registration = registration;
        this.jdbc = jdbc;
        this.localeResolver = localeResolver;
    }

    @GetMapping("/login")
    public String login() { return "login"; }

    @GetMapping("/lang")
    public String lang(@RequestParam String code, HttpServletRequest req, HttpServletResponse res,
                       @org.springframework.security.core.annotation.AuthenticationPrincipal com.medsol.security.MedsolUser me) {
        if (LANGUAGES.contains(code)) {
            localeResolver.setLocale(req, res, Locale.forLanguageTag(code));
            // langue retenue pour le compte (reprise à la prochaine connexion, e-mails)
            if (me != null) jdbc.update("UPDATE utilisateur SET langue = ? WHERE id = ?", code, me.id());
        }
        String back = req.getHeader("Referer");
        return "redirect:" + (back != null && back.contains(req.getServerName()) ? back : "/");
    }

    /** Conditions générales d'utilisation et de vente (publiques, liées depuis l'inscription). */
    @GetMapping("/conditions")
    public String conditions(Model model) {
        model.addAttribute("version", RegistrationService.CGU_VERSION);
        model.addAttribute("editeur", editeur.tous());
        model.addAttribute("editeurNom", editeur.raisonSociale());
        return "conditions";
    }

    @GetMapping("/register")
    public String registerForm(@RequestParam(required = false) String source, @RequestParam(required = false) String parrain, Model model) {
        RegistrationForm form = new RegistrationForm();
        // QR code de la fiche de présentation (?source=VISITE), lien d'une campagne… : canal présélectionné
        if (source != null && com.medsol.cabinet.RegistrationForm.SOURCES.contains(source)) form.setSource(source);
        // lien de parrainage d'un confrère (?parrain=CODE)
        if (parrain != null) {
            croissance.parrain(parrain).ifPresent(p -> {
                form.setParrain(parrain.strip().toUpperCase(java.util.Locale.ROOT));
                form.setSource("CONFRERE");
                form.setSourceDetail(p.nom());
            });
        }
        model.addAttribute("form", form);
        formulaire(model);
        return "register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegistrationForm form, BindingResult result, Model model) {
        // devise et fuseau horaire déduits du pays choisi : le médecin ne les saisit pas
        com.medsol.cabinet.Pays.de(form.getCountry()).ifPresentOrElse(p -> {
            form.setCountry(p.code());
            form.setCurrency(p.devise());
            form.setTimezone(p.fuseau());
        }, () -> result.rejectValue("country", "users.invalid"));
        if (!result.hasFieldErrors("email") && registration.emailTaken(form.getEmail())) {
            result.rejectValue("email", "register.email.taken");
        }
        if (!form.isCgu()) result.rejectValue("cgu", "register.cgu.required");
        if (form.getSource() == null || !com.medsol.cabinet.RegistrationForm.SOURCES.contains(form.getSource())) {
            result.rejectValue("source", "src.required");
        }
        if (form.getSpecialty() != null && specialties().stream().noneMatch(s -> form.getSpecialty().equals(s.get("code")))) {
            result.rejectValue("specialty", "users.invalid");   // spécialité retirée (Dentaire) ou inconnue
        }
        if (result.hasErrors()) {
            formulaire(model);
            return "register";
        }
        java.util.UUID cabinet = registration.register(form, LocaleContextHolder.getLocale().getLanguage());
        croissance.apresInscription(cabinet, form.getParrain());
        return "redirect:/login?registered";
    }

    private void formulaire(Model model) {
        String lang = LocaleContextHolder.getLocale().getLanguage();
        RegistrationForm f = (RegistrationForm) model.getAttribute("form");
        if (f != null) croissance.parrain(f.getParrain()).ifPresent(p -> model.addAttribute("parrainNom", p.nom()));
        model.addAttribute("fondateur", croissance.fondateur());
        model.addAttribute("specialties", specialties());
        model.addAttribute("sources", com.medsol.cabinet.RegistrationForm.SOURCES);
        model.addAttribute("pays", com.medsol.cabinet.Pays.TOUS.stream()
                .map(p -> Map.of("code", p.code(), "nom", p.nom(lang))).toList());
    }

    private List<Map<String, Object>> specialties() {
        String lang = LocaleContextHolder.getLocale().getLanguage();
        return jdbc.queryForList(
                "SELECT code, coalesce(libelle->>?, libelle->>'fr') AS label FROM specialite WHERE proposee ORDER BY (code = 'AUTRE'), 2", lang);
    }
}
