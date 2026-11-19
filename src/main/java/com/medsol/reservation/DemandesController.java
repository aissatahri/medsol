package com.medsol.reservation;

import com.medsol.appointment.RendezVousRepository;
import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.patient.Patient;
import com.medsol.patient.PatientForm;
import com.medsol.patient.PatientRepository;
import com.medsol.security.MedsolUser;
import com.medsol.security.Messagerie;
import com.medsol.web.Textes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Demandes de rendez-vous reçues en ligne (assistante et médecin) : accepter (fiche patient existante ou créée,
 * rendez-vous posé dans le planning) ou refuser. Le patient est prévenu par e-mail s'il en a laissé un ; un lien
 * WhatsApp prérempli permet aussi de le confirmer par message.
 */
@Controller
@RequestMapping("/planning/demandes")
public class DemandesController {

    public record Demande(UUID id, LocalDateTime debut, LocalDateTime fin, String nom, String prenom, String telephone, String email,
                          String motif, String langue, String statut, java.time.Instant creeLe, UUID patientId) {
        public String nomComplet() { return (nom == null ? "" : nom.toUpperCase()) + " " + (prenom == null ? "" : prenom); }
    }

    private final JdbcTemplate jdbc;
    private final PatientRepository patients;
    private final RendezVousRepository rdv;
    private final CabinetService cabinets;
    private final AuditService audit;
    private final Messagerie mail;
    private final Textes textes;
    private final org.springframework.context.MessageSource messages;

    public DemandesController(JdbcTemplate jdbc, PatientRepository patients, RendezVousRepository rdv, CabinetService cabinets,
                              AuditService audit, Messagerie mail, Textes textes, org.springframework.context.MessageSource messages) {
        this.jdbc = jdbc;
        this.patients = patients;
        this.rdv = rdv;
        this.cabinets = cabinets;
        this.audit = audit;
        this.mail = mail;
        this.textes = textes;
        this.messages = messages;
    }

    private List<Demande> lire(String condition, Object... args) {
        ZoneId z = cabinets.current().zone();
        return jdbc.query("""
                SELECT id, debut, fin, nom, prenom, telephone, email, motif, langue, statut, cree_le, patient_id
                FROM reservation WHERE\s""" + condition,
                (rs, i) -> new Demande(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant().atZone(z).toLocalDateTime(),
                        rs.getTimestamp(3).toInstant().atZone(z).toLocalDateTime(), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getTimestamp(11).toInstant(),
                        rs.getObject(12, UUID.class)), args);
    }

    @GetMapping
    public String page(Model model) {
        List<Demande> enAttente = lire("statut = 'EN_ATTENTE' AND debut > now() ORDER BY debut");
        // fiches possibles : même téléphone (chiffres) ou même nom
        Map<UUID, List<Patient>> candidats = new HashMap<>();
        for (Demande d : enAttente) {
            String chiffres = d.telephone().replaceAll("\\D", "");
            Set<UUID> vus = new HashSet<>();
            List<Patient> l = new ArrayList<>();
            for (String q : List.of(chiffres.length() >= 6 ? chiffres.substring(chiffres.length() - 6) : chiffres, d.nom())) {
                if (q == null || q.length() < 2) continue;
                for (Patient p : patients.search(q, 0, 5).items()) if (vus.add(p.id())) l.add(p);
            }
            candidats.put(d.id(), l);
        }
        model.addAttribute("demandes", enAttente);
        model.addAttribute("candidats", candidats);
        model.addAttribute("traitees", lire("statut <> 'EN_ATTENTE' ORDER BY coalesce(traite_le, cree_le) DESC LIMIT 20"));
        return "planning/demandes";
    }

    @PostMapping("/{id}/accepter")
    public String accepter(@PathVariable UUID id, @RequestParam(required = false) UUID patientId,
                           @AuthenticationPrincipal MedsolUser user, RedirectAttributes ra) {
        Demande d = lire("id = ? AND statut = 'EN_ATTENTE'", id).stream().findFirst().orElse(null);
        if (d == null) {
            ra.addFlashAttribute("erreur", textes.t("res.admin.gone"));
            return "redirect:/planning/demandes";
        }
        UUID patient = patientId;
        if (patient == null || patients.find(patient).isEmpty()) {
            PatientForm f = new PatientForm();
            f.setNom(d.nom());
            f.setPrenom(d.prenom());
            f.setTelephone(d.telephone().replaceAll("[^0-9+()\\-. ]", ""));
            f.setEmail(d.email());
            f.setConsentement(true);   // consentement donné sur la page de réservation
            patient = patients.create(f, user);
            audit.log("PATIENT_CREATION", "patient", patient);
        }
        String titre = (d.motif() == null ? "" : d.motif() + " ") + textes.t("res.admin.online");
        UUID rendezVous = rdv.creer(patient, user.id(), d.debut().toLocalDate(), d.debut().toLocalTime(), d.fin().toLocalTime(),
                titre.strip(), user.fullName());
        jdbc.update("UPDATE reservation SET statut = 'ACCEPTEE', traite_le = now(), traite_par = ?, patient_id = ?, rdv_id = ? WHERE id = ?",
                user.id(), patient, rendezVous, id);
        audit.log("RESERVATION_ACCEPTEE", "reservation", id);
        prevenir(d, "res.mail.accepted");
        ra.addFlashAttribute("succes", textes.t("res.admin.accepted", d.nomComplet()));
        ra.addFlashAttribute("whatsapp", whatsapp(d, "res.wa.accepted"));
        return "redirect:/planning/demandes";
    }

    @PostMapping("/{id}/refuser")
    public String refuser(@PathVariable UUID id, @AuthenticationPrincipal MedsolUser user, RedirectAttributes ra) {
        Demande d = lire("id = ? AND statut = 'EN_ATTENTE'", id).stream().findFirst().orElse(null);
        if (d != null && jdbc.update("UPDATE reservation SET statut = 'REFUSEE', traite_le = now(), traite_par = ? WHERE id = ? AND statut = 'EN_ATTENTE'",
                user.id(), id) == 1) {
            audit.log("RESERVATION_REFUSEE", "reservation", id);
            prevenir(d, "res.mail.refused");
            ra.addFlashAttribute("succes", textes.t("res.admin.refused", d.nomComplet()));
            ra.addFlashAttribute("whatsapp", whatsapp(d, "res.wa.refused"));
        }
        return "redirect:/planning/demandes";
    }

    /** E-mail au patient, dans la langue de sa demande. */
    private void prevenir(Demande d, String cle) {
        if (d.email() == null) return;
        Locale l = Locale.forLanguageTag(d.langue());
        String quand = d.debut().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy HH:mm", l));
        String cabinet = cabinets.current().nom();
        mail.envoyer(d.email(), messages.getMessage(cle + ".subject", new Object[]{cabinet}, l),
                messages.getMessage(cle + ".body", new Object[]{d.prenom(), cabinet, quand}, l));
    }

    /** Lien WhatsApp prérempli (numéro international si possible) pour prévenir le patient par message. */
    private String whatsapp(Demande d, String cle) {
        String numero = com.medsol.appointment.RappelsController.international(d.telephone(), cabinets.current().pays());
        if (numero == null) return null;
        Locale l = Locale.forLanguageTag(d.langue());
        String quand = d.debut().format(DateTimeFormatter.ofPattern("EEEE d MMMM HH:mm", l));
        String texte = messages.getMessage(cle, new Object[]{d.prenom(), cabinets.current().nom(), quand}, l);
        return "https://wa.me/" + numero + "?text=" + URLEncoder.encode(texte, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
