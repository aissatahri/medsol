package com.medsol.web;

import com.medsol.cabinet.CabinetService;
import com.medsol.queue.QueueEntry;
import com.medsol.queue.QueueRepository;
import com.medsol.security.MedsolUser;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** Tableau de bord repris de l'accueil de SGCM-Cardio Web (compteurs, liste d'attente, rendez-vous du jour). */
@Controller
public class HomeController {

    public record Compteurs(long patients, long enAttente, long passagesDuJour, long rendezVousDuJour) {}

    public record RendezVous(UUID patientId, String nomComplet, Instant debut, String motif, String statut) {}

    private final JdbcTemplate jdbc;
    private final CabinetService cabinets;
    private final QueueRepository queue;

    private final com.medsol.abonnement.Editeur editeur;
    private final JdbcTemplate plateforme;
    @org.springframework.beans.factory.annotation.Autowired private com.medsol.abonnement.Croissance croissance;

    public HomeController(JdbcTemplate jdbc, CabinetService cabinets, QueueRepository queue, com.medsol.abonnement.Editeur editeur,
                          @org.springframework.beans.factory.annotation.Qualifier("plateforme") JdbcTemplate plateforme) {
        this.jdbc = jdbc;
        this.cabinets = cabinets;
        this.queue = queue;
        this.editeur = editeur;
        this.plateforme = plateforme;
    }

    /**
     * Page d'accueil publique (visiteur non connecté) : présentation, tarif, questions fréquentes. « Demander une
     * démonstration » ouvre WhatsApp vers le téléphone de l'éditeur (Plateforme › Éditeur), sinon un e-mail.
     */
    private String vitrine(Model model) {
        var e = editeur.tous();
        String numero = com.medsol.appointment.RappelsController.international(e.get("telephone"), "MA");
        String message = java.net.URLEncoder.encode("Bonjour, je souhaite une démonstration de MedSol pour mon cabinet.",
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        model.addAttribute("demo", numero != null ? "https://wa.me/" + numero + "?text=" + message
                : !e.get("email").isBlank() ? "mailto:" + e.get("email") + "?subject=" + message : null);
        model.addAttribute("editeur", e);
        model.addAttribute("editeurNom", editeur.raisonSociale());
        model.addAttribute("fondateur", croissance.fondateur());
        model.addAttribute("prix", plateforme.query(
                "SELECT mensuel, annuel FROM formule_prix WHERE formule_code = 'CABINET' AND devise = 'MAD'",
                (rs, i) -> java.util.Map.of("mensuel", rs.getBigDecimal(1), "annuel", rs.getBigDecimal(2))).stream().findFirst().orElse(null));
        return "accueil";
    }

    @GetMapping("/")
    public String home(@AuthenticationPrincipal MedsolUser user, Model model) {
        if (user == null) return vitrine(model);
        // L'administrateur de MedSol n'a pas de cabinet à gérer : son accueil est l'espace Plateforme
        if (user.plateforme()) return "redirect:/plateforme";
        // Pas de filtre cabinet_id : c'est la Row-Level Security qui limite au cabinet connecté.
        CabinetService.Cabinet cab = cabinets.current();
        LocalDate today = cab.today();
        Timestamp debut = Timestamp.from(today.atStartOfDay(cab.zone()).toInstant());
        Timestamp fin = Timestamp.from(today.plusDays(1).atStartOfDay(cab.zone()).toInstant());
        model.addAttribute("user", user);
        model.addAttribute("jourTexte", today.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", LocaleContextHolder.getLocale())));
        model.addAttribute("compteurs", new Compteurs(
                jdbc.queryForObject("SELECT count(*) FROM patient WHERE NOT archive", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM file_attente WHERE jour = ? AND statut = 'EN_ATTENTE'", Long.class, Date.valueOf(today)),
                jdbc.queryForObject("SELECT count(*) FROM file_attente WHERE jour = ? AND statut <> 'ABSENT'", Long.class, Date.valueOf(today)),
                jdbc.queryForObject("SELECT count(*) FROM rendez_vous WHERE debut >= ? AND debut < ? AND statut NOT IN ('ANNULE','ABSENT')",
                        Long.class, debut, fin)));
        model.addAttribute("file", file(today));
        model.addAttribute("rendezVous", jdbc.query("""
                SELECT r.patient_id, upper(p.nom) || ' ' || p.prenom, r.debut, r.motif, r.statut
                FROM rendez_vous r JOIN patient p ON p.id = r.patient_id
                WHERE r.debut >= ? AND r.debut < ? AND r.statut NOT IN ('ANNULE') ORDER BY r.debut""",
                (rs, i) -> new RendezVous(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getString(4), rs.getString(5)), debut, fin));
        return "home";
    }

    /** Ancienne adresse des rendez-vous : le planning. */
    @GetMapping("/appointments")
    public String appointments() { return "redirect:/planning"; }

    /** Liste d'attente relue toutes les 15 s. */
    @GetMapping("/waiting")
    public String waiting(Model model) {
        model.addAttribute("file", file(cabinets.current().today()));
        return "home :: file";
    }

    private List<QueueEntry> file(LocalDate today) {
        return queue.today(today, List.of("EN_ATTENTE", "EN_CONSULTATION"));
    }
}
