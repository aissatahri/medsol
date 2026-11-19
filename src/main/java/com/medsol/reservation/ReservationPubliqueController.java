package com.medsol.reservation;

import com.medsol.security.Messagerie;
import com.medsol.web.Textes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pages publiques de la prise de rendez-vous en ligne (sans compte) : créneaux libres de la semaine, formulaire,
 * confirmation avec lien d'annulation, annulation. Le cabinet valide ensuite chaque demande (DemandesController).
 * Protections : limitation par adresse IP (LimiteurTentatives), champ piège pour les robots, 3 demandes en attente
 * au plus par téléphone, jeton CSRF des formulaires.
 */
@Controller
@RequestMapping("/r")
public class ReservationPubliqueController {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern TELEPHONE = Pattern.compile("^[0-9+()\\-. ]{6,30}$");
    private static final int JOURS = 7;

    private final ReservationPublique reservations;
    private final Messagerie mail;
    private final Textes textes;
    private final String baseUrl;

    public ReservationPubliqueController(ReservationPublique reservations, Messagerie mail, Textes textes,
                                         @Value("${medsol.base-url:}") String baseUrl) {
        this.reservations = reservations;
        this.mail = mail;
        this.textes = textes;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String langue() { return LocaleContextHolder.getLocale().getLanguage(); }

    private String introuvable(Model model, HttpServletResponse res) {
        res.setStatus(404);
        model.addAttribute("etape", "introuvable");
        return "reservation/public";
    }

    @GetMapping("/{lien}")
    public String creneaux(@PathVariable String lien, @RequestParam(required = false) String du, Model model, HttpServletResponse res) {
        Optional<ReservationPublique.Cabinet> c = reservations.cabinet(lien, langue());
        if (c.isEmpty()) return introuvable(model, res);
        LocalDate aujourdhui = LocalDate.now(c.get().zone());
        LocalDate debut;
        try {
            debut = du == null ? aujourdhui : LocalDate.parse(du);
        } catch (DateTimeParseException e) {
            debut = aujourdhui;
        }
        if (debut.isBefore(aujourdhui)) debut = aujourdhui;
        LocalDate dernier = aujourdhui.plusDays(c.get().horizon());
        if (debut.isAfter(dernier)) debut = dernier;
        model.addAttribute("etape", "creneaux");
        model.addAttribute("c", c.get());
        model.addAttribute("lien", lien);
        model.addAttribute("jours", reservations.creneaux(c.get(), debut, JOURS));
        model.addAttribute("precedent", debut.isAfter(aujourdhui) ? debut.minusDays(JOURS).isBefore(aujourdhui) ? aujourdhui : debut.minusDays(JOURS) : null);
        model.addAttribute("suivant", debut.plusDays(JOURS).isAfter(dernier) ? null : debut.plusDays(JOURS));
        return "reservation/public";
    }

    @GetMapping("/{lien}/creneau")
    public String formulaire(@PathVariable String lien, @RequestParam String debut, Model model, HttpServletResponse res) {
        Optional<ReservationPublique.Cabinet> c = reservations.cabinet(lien, langue());
        if (c.isEmpty()) return introuvable(model, res);
        LocalDateTime d;
        try {
            d = LocalDateTime.parse(debut);
        } catch (DateTimeParseException e) {
            return "redirect:/r/" + lien;
        }
        model.addAttribute("etape", "formulaire");
        model.addAttribute("c", c.get());
        model.addAttribute("lien", lien);
        model.addAttribute("debut", d);
        return "reservation/public";
    }

    @PostMapping("/{lien}")
    public String demander(@PathVariable String lien, @RequestParam String debut, @RequestParam(defaultValue = "") String nom,
                           @RequestParam(defaultValue = "") String prenom, @RequestParam(defaultValue = "") String telephone,
                           @RequestParam(defaultValue = "") String email, @RequestParam(defaultValue = "") String motif,
                           @RequestParam(defaultValue = "false") boolean consentement, @RequestParam(defaultValue = "") String site,
                           HttpServletRequest req, Model model, HttpServletResponse res) {
        Optional<ReservationPublique.Cabinet> c = reservations.cabinet(lien, langue());
        if (c.isEmpty()) return introuvable(model, res);
        LocalDateTime d;
        try {
            d = LocalDateTime.parse(debut);
        } catch (DateTimeParseException e) {
            return "redirect:/r/" + lien;
        }
        model.addAttribute("c", c.get());
        model.addAttribute("lien", lien);
        model.addAttribute("debut", d);
        String n = nom.strip(), p = prenom.strip(), t = telephone.strip(), e = email.strip(), m = motif.strip();
        String erreur = null;
        if (!site.isEmpty()) erreur = "res.error.invalid";                                  // champ piège rempli : robot
        else if (n.isEmpty() || p.isEmpty() || n.length() > 100 || p.length() > 100) erreur = "res.error.name";
        else if (!TELEPHONE.matcher(t).matches()) erreur = "res.error.phone";
        else if (!e.isEmpty() && (e.length() > 200 || !EMAIL.matcher(e).matches())) erreur = "res.error.email";
        else if (m.length() > 200) erreur = "res.error.reason";
        else if (!consentement) erreur = "res.error.consent";
        if (erreur != null) {
            model.addAttribute("etape", "formulaire");
            model.addAttribute("erreur", erreur);
            model.addAttribute("saisie", new ReservationPublique.Demande(n, p, t, e, m, langue()));
            return "reservation/public";
        }
        ReservationPublique.Reponse r = reservations.demander(c.get(), d,
                new ReservationPublique.Demande(n, p, t, e.isEmpty() ? null : e, m.isEmpty() ? null : m, langue()), req.getRemoteAddr());
        switch (r.resultat()) {
            case CRENEAU_PRIS -> { return "redirect:/r/" + lien + "?du=" + d.toLocalDate() + "&pris"; }
            case TROP_DE_DEMANDES -> {
                model.addAttribute("etape", "formulaire");
                model.addAttribute("erreur", "res.error.tooMany");
                return "reservation/public";
            }
            default -> { }
        }
        String annulation = base() + "/r/annuler/" + r.jeton();
        if (!e.isEmpty()) {
            String quand = d.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy HH:mm", LocaleContextHolder.getLocale()));
            mail.envoyer(e, textes.t("res.mail.subject", c.get().nom()), textes.t("res.mail.body", p, c.get().nom(), quand, annulation));
        }
        model.addAttribute("etape", "confirmee");
        model.addAttribute("annulation", annulation);
        return "reservation/public";
    }

    @GetMapping("/annuler/{jeton}")
    public String annulation(@PathVariable String jeton, Model model) {
        model.addAttribute("etape", "annuler");
        model.addAttribute("jeton", jeton);
        model.addAttribute("a", reservations.annulable(jeton).orElse(null));
        return "reservation/public";
    }

    @PostMapping("/annuler/{jeton}")
    public String annuler(@PathVariable String jeton, Model model) {
        model.addAttribute("etape", reservations.annuler(jeton) ? "annulee" : "annuler");
        model.addAttribute("jeton", jeton);
        model.addAttribute("a", null);
        return "reservation/public";
    }

    private String base() {
        return baseUrl.isBlank() ? ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() : baseUrl;
    }
}
