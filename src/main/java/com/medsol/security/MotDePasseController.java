package com.medsol.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pages publiques : mot de passe oublié (lien de 30 minutes) et invitation (l'assistante choisit son mot de passe).
 * La réponse à « mot de passe oublié » est la même que l'adresse existe ou non (on ne révèle pas les comptes).
 */
@Controller
public class MotDePasseController {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final Jetons jetons;
    private final LiensCompte liens;
    private final PasswordEncoder encoder;
    private final LimiteurTentatives limiteur;

    public MotDePasseController(Jetons jetons, LiensCompte liens, PasswordEncoder encoder, LimiteurTentatives limiteur) {
        this.limiteur = limiteur;
        this.jetons = jetons;
        this.liens = liens;
        this.encoder = encoder;
    }

    @GetMapping("/mot-de-passe-oublie")
    public String oubli() { return "compte/oubli"; }

    @PostMapping("/mot-de-passe-oublie")
    public String demander(@RequestParam String email, HttpServletRequest req) {
        String e = email.strip();
        // au plus 3 e-mails par heure pour une même adresse (pas d'inondation de la boîte d'un médecin)
        if (e.length() <= 200 && EMAIL.matcher(e).matches()
                && limiteur.tenter("oubli-email", e.toLowerCase(java.util.Locale.ROOT), 3, java.time.Duration.ofHours(1)) == 0) {
            jetons.compteActif(e).ifPresent(c -> {
                liens.reinitialisation(c);
                jetons.journal(c.id(), "MOT_DE_PASSE_OUBLIE", req.getRemoteAddr());
            });
        }
        return "redirect:/mot-de-passe-oublie?envoye";
    }

    @GetMapping("/reinitialisation/{jeton}")
    public String reinitialisation(@PathVariable String jeton, Model model) {
        return formulaire(jeton, "REINITIALISATION", model);
    }

    @PostMapping("/reinitialisation/{jeton}")
    public String reinitialiser(@PathVariable String jeton, @RequestParam String password, @RequestParam String confirmation,
                                HttpServletRequest req, Model model) {
        return enregistrer(jeton, "REINITIALISATION", password, confirmation, req, model, "/login?reinitialise");
    }

    @GetMapping("/invitation/{jeton}")
    public String invitation(@PathVariable String jeton, Model model) {
        return formulaire(jeton, "INVITATION", model);
    }

    @PostMapping("/invitation/{jeton}")
    public String accepter(@PathVariable String jeton, @RequestParam String password, @RequestParam String confirmation,
                           HttpServletRequest req, Model model) {
        return enregistrer(jeton, "INVITATION", password, confirmation, req, model, "/login?bienvenue");
    }

    private String formulaire(String jeton, String type, Model model) {
        Optional<Jetons.Compte> c = jetons.valide(jeton, type);
        model.addAttribute("type", type);
        model.addAttribute("jeton", jeton);
        model.addAttribute("compte", c.orElse(null));
        return "compte/nouveau";
    }

    private String enregistrer(String jeton, String type, String password, String confirmation, HttpServletRequest req,
                               Model model, String succes) {
        String erreur = password.length() < 10 || password.length() > 100 ? "users.password.short"
                : !password.equals(confirmation) ? "users.password.mismatch" : null;
        if (erreur == null) {
            Optional<Jetons.Compte> c = jetons.consommer(jeton, type, encoder.encode(password));
            if (c.isPresent()) {
                jetons.journal(c.get().id(), "INVITATION".equals(type) ? "INVITATION_ACCEPTEE" : "MOT_DE_PASSE_REINITIALISE", req.getRemoteAddr());
                return "redirect:" + succes;
            }
        }
        formulaire(jeton, type, model);
        model.addAttribute("error", erreur);
        return "compte/nouveau";
    }
}
