package com.medsol.dossier;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.patient.Patient;
import com.medsol.patient.PatientRepository;
import com.medsol.security.MedsolUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Dossier médical du patient, organisé comme l'application de bureau et SGCM-Cardio Web : mêmes onglets dans le
 * même ordre, la liste des consultations à gauche des onglets qui portent sur une consultation. Médecin uniquement.
 */
@Controller
public class DossierController {

    public static final List<String> ONGLETS = List.of("info", "consultation", "analyses", "imageries", "ordonnances", "rendez-vous", "paiement", "fichiers", "courbes");
    private static final List<String> PAR_CONSULTATION = List.of("consultation", "analyses", "imageries", "ordonnances");

    private final PatientRepository patients;
    private final ConsultationRepository consultations;
    private final OrdonnanceRepository ordonnances;
    private final ExamenRepository examens;
    private final AllergiesRepository allergies;
    private final Catalogues catalogues;
    private final CabinetService cabinets;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final com.medsol.caisse.CaisseRepository caisse;
    private final AlertesPrescription alertes;

    private final com.medsol.web.Textes textes;

    public DossierController(PatientRepository patients, ConsultationRepository consultations, OrdonnanceRepository ordonnances,
                             ExamenRepository examens, AllergiesRepository allergies, Catalogues catalogues,
                             CabinetService cabinets, AuditService audit, JdbcTemplate jdbc,
                             com.medsol.caisse.CaisseRepository caisse, AlertesPrescription alertes, com.medsol.web.Textes textes) {
        this.textes = textes;
        this.alertes = alertes;
        this.caisse = caisse;
        this.patients = patients;
        this.consultations = consultations;
        this.ordonnances = ordonnances;
        this.examens = examens;
        this.allergies = allergies;
        this.catalogues = catalogues;
        this.cabinets = cabinets;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public static String adresse(UUID patientId, String onglet, UUID consultationId) {
        return "/patients/" + patientId + "/dossier?onglet=" + onglet + (consultationId == null ? "" : "&c=" + consultationId);
    }

    @GetMapping("/patients/{id}/dossier")
    public String dossier(@PathVariable UUID id, @RequestParam(defaultValue = "info") String onglet,
                          @RequestParam(required = false) UUID c, Model model) {
        String o = ONGLETS.contains(onglet) ? onglet : "info";
        boolean parConsultation = PAR_CONSULTATION.contains(o);
        Patient p = patients.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<ConsultationRepository.Visite> visites = consultations.visites(id);
        ConsultationRepository.Visite selection = null;
        if (parConsultation && !visites.isEmpty()) {
            UUID voulue = c != null ? c : visites.get(0).id();
            selection = visites.stream().filter(v -> v.id().equals(voulue)).findFirst().orElse(null);
            if (selection == null) return "redirect:" + adresse(id, o, null);   // consultation d'un autre patient
        }
        audit.log("LECTURE", "dossier", id);

        switch (o) {
            case "info" -> {
                model.addAttribute("antecedents", allergies.antecedents(id));
                model.addAttribute("allergies", allergies.liste(id));
                model.addAttribute("typesAntecedent", AllergiesRepository.TYPES_ANTECEDENT);
            }
            case "rendez-vous" -> model.addAttribute("rendezVous", rendezVous(id));
            case "paiement" -> {
                // Comme le bureau : tableau des consultations (montant, versé, reste) et actes de la consultation choisie
                List<com.medsol.caisse.CaisseRepository.Ligne> lignes = caisse.patient(id);
                model.addAttribute("paiements", lignes);
                model.addAttribute("devise", cabinets.current().devise());
                UUID voulue = c != null ? c : lignes.isEmpty() ? null : lignes.get(lignes.size() - 1).consultationId();
                model.addAttribute("paiementChoisi", lignes.stream().anyMatch(l -> l.consultationId().equals(voulue)) ? voulue : null);
            }
            default -> {
                if (selection != null) {
                    UUID cid = selection.id();
                    switch (o) {
                        case "consultation" -> {
                            model.addAttribute("saisie", consultations.lire(cid));
                            model.addAttribute("typesChamps", catalogues.champs());
                            model.addAttribute("typesConstantes", catalogues.constantes());
                        }
                        case "analyses", "imageries" -> {
                            ExamenRepository.Genre g = "analyses".equals(o) ? ExamenRepository.Genre.ANALYSE : ExamenRepository.Genre.IMAGERIE;
                            model.addAttribute("g", g);
                            model.addAttribute("examens", examens.liste(g, cid));
                        }
                        case "ordonnances" -> {
                            model.addAttribute("redactions", com.medsol.documents.Redactions.pour(cabinets.current().specialite()));
                            model.addAttribute("lignes", ordonnances.lignes(cid));
                            model.addAttribute("alertes", alertes.ordonnance(cid));
                            model.addAttribute("precedente", ordonnances.precedenteAvecOrdonnance(cid).orElse(null));
                        }
                        default -> { }
                    }
                }
            }
        }
        model.addAttribute("patient", p);
        model.addAttribute("onglet", o);
        model.addAttribute("visites", visites);
        List<ConsultationRepository.Visite> chrono = new ArrayList<>(visites);
        Collections.reverse(chrono);   // ordre du bureau : n° 1 = la plus ancienne
        model.addAttribute("visitesChrono", chrono);
        model.addAttribute("selection", selection);
        model.addAttribute("parConsultation", parConsultation);
        model.addAttribute("aujourdhui", cabinets.current().today());
        if (selection != null) {
            model.addAttribute("consultation", new ConsultationRepository.Consultation(selection.id(), id, selection.date()));
            model.addAttribute("consultationId", selection.id());
        }
        return "dossier/dossier";
    }

    /** « Ajouter » : consultation à la date choisie (une seule par jour, rouverte si elle existe ; jamais dans le futur). */
    @PostMapping("/patients/{id}/dossier/consultations")
    public String ajouter(@PathVariable UUID id, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          @AuthenticationPrincipal MedsolUser user) {
        patients.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        LocalDate aujourdhui = cabinets.current().today();
        LocalDate jour = date == null || date.isAfter(aujourdhui) ? aujourdhui : date;
        UUID cid = consultations.duJour(id, user.id(), jour);
        audit.log("OUVERTURE", "consultation", cid);
        return "redirect:" + adresse(id, "consultation", cid);
    }

    @PostMapping("/patients/{id}/dossier/consultations/{c}/date")
    public String changerDate(@PathVariable UUID id, @PathVariable UUID c,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                              @RequestParam(defaultValue = "consultation") String onglet, RedirectAttributes flash) {
        consultation(id, c);
        String retour = "redirect:" + adresse(id, ONGLETS.contains(onglet) ? onglet : "consultation", c);
        if (date.isAfter(cabinets.current().today())) {
            flash.addFlashAttribute("erreur", textes.t("msg.consultation.future"));
        } else if (!consultations.changerDate(c, date)) {
            flash.addFlashAttribute("erreur", textes.t("msg.consultation.dateTaken", date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
        } else {
            audit.log("MODIFICATION_DATE", "consultation", c);
            flash.addFlashAttribute("succes", textes.t("msg.consultation.dateChanged"));
        }
        return retour;
    }

    @PostMapping("/patients/{id}/dossier/consultations/{c}/supprimer")
    public String supprimer(@PathVariable UUID id, @PathVariable UUID c, @RequestParam(required = false) String motif,
                            @AuthenticationPrincipal MedsolUser user, RedirectAttributes flash) {
        consultation(id, c);
        boolean corbeille = consultations.supprimer(c, motif, user.id());
        audit.log(corbeille ? "CORBEILLE" : "SUPPRESSION", "consultation", c);
        flash.addFlashAttribute("succes", corbeille
                ? textes.t("msg.consultation.binned")
                : textes.t("msg.consultation.deleted"));
        return "redirect:" + adresse(id, "consultation", null);
    }

    // ── Antécédents et allergies (onglet Info) ─────────────────────────────

    @PostMapping("/patients/{id}/antecedents")
    public String ajouterAntecedent(@PathVariable UUID id, @RequestParam String type, @RequestParam String description,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                    @RequestParam(required = false) String note, RedirectAttributes flash) {
        patients.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (description.isBlank() || description.length() > 500) {
            flash.addFlashAttribute("erreur", textes.t("msg.history.required"));
        } else {
            allergies.ajouterAntecedent(id, type, description, date, note);
            audit.log("ANTECEDENT_AJOUT", "patient", id);
        }
        return "redirect:" + adresse(id, "info", null) + "#antecedents";
    }

    @PostMapping("/patients/{id}/antecedents/{a}/supprimer")
    public String supprimerAntecedent(@PathVariable UUID id, @PathVariable UUID a) {
        if (allergies.supprimerAntecedent(id, a)) audit.log("ANTECEDENT_SUPPRESSION", "patient", id);
        return "redirect:" + adresse(id, "info", null) + "#antecedents";
    }

    @PostMapping("/patients/{id}/allergies")
    public String ajouterAllergie(@PathVariable UUID id, @RequestParam String nom,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                  RedirectAttributes flash) {
        patients.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (nom.isBlank() || nom.length() > 200) {
            flash.addFlashAttribute("erreur", textes.t("msg.allergy.required"));
        } else {
            allergies.ajouter(id, nom, date);
            audit.log("ALLERGIE_AJOUT", "patient", id);
        }
        return "redirect:" + adresse(id, "info", null) + "#allergies";
    }

    @PostMapping("/patients/{id}/allergies/{a}/supprimer")
    public String supprimerAllergie(@PathVariable UUID id, @PathVariable UUID a) {
        if (allergies.supprimer(id, a)) audit.log("ALLERGIE_SUPPRESSION", "patient", id);
        return "redirect:" + adresse(id, "info", null) + "#allergies";
    }

    /** Bandeau rouge des allergies, visible depuis tous les onglets du dossier et la consultation. */
    @GetMapping("/patients/{id}/allergies/bandeau")
    public String bandeau(@PathVariable UUID id, Model model) {
        model.addAttribute("allergies", allergies.liste(id));
        return "dossier/allergies :: bandeau";
    }

    // ─────────────────────────────────────────────────────────────────────

    private void consultation(UUID patientId, UUID c) {
        consultations.trouver(c).filter(x -> x.patientId().equals(patientId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public record RendezVous(String motif, java.time.Instant debut, java.time.Instant fin, String statut) {}

    private List<RendezVous> rendezVous(UUID patientId) {
        return jdbc.query("SELECT motif, debut, fin, statut FROM rendez_vous WHERE patient_id = ? ORDER BY debut DESC",
                (rs, i) -> new RendezVous(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(),
                        rs.getString(4)), patientId);
    }
}
