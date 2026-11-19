package com.medsol.caisse;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.dossier.ConsultationRepository;
import com.medsol.documents.FacturesPdf;
import com.medsol.patient.Patient;
import com.medsol.patient.PatientRepository;
import com.medsol.security.MedsolUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Facture du patient (actes cochés, montant ajustable, date ; numéro unique jamais redonné), reçu de paiement d'une
 * consultation et clôture de caisse, repris de FactureController de SGCM-Cardio. Médecin et assistante.
 */
@Controller
public class FactureController {

    private static final DateTimeFormatter NUMERO = DateTimeFormatter.ofPattern("ddMMyyyy");

    /** Ligne du formulaire : acte du catalogue, coché d'avance s'il a été payé lors de la consultation choisie. */
    public record Choix(UUID acteId, String nom, BigDecimal prix, boolean coche) {}

    private final CaisseRepository caisse;
    private final PatientRepository patients;
    private final ConsultationRepository consultations;
    private final CabinetService cabinets;
    private final FacturesPdf pdf;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public FactureController(CaisseRepository caisse, PatientRepository patients, ConsultationRepository consultations,
                             CabinetService cabinets, FacturesPdf pdf, AuditService audit, JdbcTemplate jdbc) {
        this.caisse = caisse;
        this.patients = patients;
        this.consultations = consultations;
        this.cabinets = cabinets;
        this.pdf = pdf;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /** Depuis la caisse ou la consultation : formulaire de facture du patient, actes payés cochés. */
    @GetMapping("/caisse/{id}/facture")
    public String depuisConsultation(@PathVariable UUID id) {
        UUID patient = consultations.trouver(id).orElseThrow(FactureController::introuvable).patientId();
        return "redirect:/patients/" + patient + "/facture?consultation=" + id;
    }

    @GetMapping("/patients/{id}/facture")
    public String formulaire(@PathVariable UUID id, @RequestParam(required = false) UUID consultation, Model model) {
        Patient patient = patients.find(id).orElseThrow(FactureController::introuvable);
        Set<UUID> payes = consultation == null ? Set.of() : caisse.actes(consultation).stream()
                .filter(CaisseRepository.ActeRealise::paye).map(CaisseRepository.ActeRealise::acteId).collect(Collectors.toSet());
        model.addAttribute("patient", patient);
        model.addAttribute("choix", caisse.catalogue(false).stream().map(a -> new Choix(a.id(), a.nom(), a.prix(), payes.contains(a.id()))).toList());
        model.addAttribute("date", cabinets.current().today());
        model.addAttribute("consultation", consultation);
        model.addAttribute("devise", cabinets.current().devise());
        return "caisse/facture";
    }

    /** Numéro réservé puis PDF ; la facture émise est enregistrée et son numéro n'est jamais redonné. */
    @PostMapping("/patients/{id}/facture")
    public ResponseEntity<byte[]> imprimer(@PathVariable UUID id, @RequestParam Map<String, String> formulaire,
                                           @AuthenticationPrincipal MedsolUser user) {
        Patient patient = patients.find(id).orElseThrow(FactureController::introuvable);
        List<FacturesPdf.Article> articles = new ArrayList<>();
        for (CaisseRepository.Acte a : caisse.catalogue(false)) {
            if (!formulaire.containsKey("acte_" + a.id())) continue;
            BigDecimal prix;
            try {
                prix = new BigDecimal(formulaire.getOrDefault("prix_" + a.id(), "").strip().replace(',', '.'));
            } catch (NumberFormatException e) {
                prix = a.prix();
            }
            articles.add(new FacturesPdf.Article(a.nom(), Monnaie.arrondi(prix.max(BigDecimal.ZERO))));
        }
        if (articles.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cochez au moins un acte pour générer la facture.");
        LocalDate date;
        try {
            date = LocalDate.parse(formulaire.getOrDefault("date", ""));
        } catch (DateTimeParseException e) {
            date = cabinets.current().today();
        }
        String numero = String.format("FA_%s-%03d", date.format(NUMERO), cabinets.nextValue("facture:" + date));
        BigDecimal total = articles.stream().map(FacturesPdf.Article::prix).reduce(BigDecimal.ZERO, BigDecimal::add);
        jdbc.update("""
                INSERT INTO facture (cabinet_id, numero, patient_id, jour, total, lignes, cree_par)
                VALUES (current_cabinet(), ?, ?, ?, ?, ?, ?)""", numero, id, Date.valueOf(date), total,
                articles.stream().map(a -> a.acte().replace(';', ',') + ";" + a.prix()).collect(Collectors.joining("\n")), user.id());
        audit.log("FACTURE " + numero, "patient", id);
        byte[] contenu = pdf.facture(entete(user), patient.nomComplet(), String.valueOf(patient.numero()), date, numero, articles);
        return reponse(contenu, "Facture_" + nom(patient) + "_" + numero.substring(3));
    }

    @GetMapping("/caisse/{id}/recu")
    public ResponseEntity<byte[]> recu(@PathVariable UUID id, @AuthenticationPrincipal MedsolUser user) {
        ConsultationRepository.Consultation c = consultations.trouver(id).orElseThrow(FactureController::introuvable);
        Patient patient = patients.find(c.patientId()).orElseThrow(FactureController::introuvable);
        List<CaisseRepository.ActeRealise> actes = caisse.actes(id);
        List<CaisseRepository.Versement> versements = caisse.versements(id);
        if (versements.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucun versement enregistré pour cette consultation");
        CaisseRepository.Versement dernier = versements.get(versements.size() - 1);
        audit.log("RECU R-" + dernier.numero(), "consultation", id);
        byte[] contenu = pdf.recu(entete(user), patient.nomComplet(), String.valueOf(patient.numero()), c.date(), dernier.numero(), dernier.jour(),
                actes.stream().map(x -> new FacturesPdf.ActeRecu(x.nom(), x.prix(), x.paye())).toList(),
                versements.stream().map(x -> new FacturesPdf.VersementRecu(x.jour(), x.modeLibelle(), x.montant())).toList(), caisse.total(id));
        return reponse(contenu, "Recu_" + nom(patient) + "_R-" + dernier.numero());
    }

    /** Clôture de caisse du jour : versements encaissés ce jour-là, par mode. */
    @GetMapping("/caisse/cloture")
    public ResponseEntity<byte[]> cloture(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate jour,
                                          @AuthenticationPrincipal MedsolUser user) {
        LocalDate d = jour == null ? cabinets.current().today() : jour;
        List<FacturesPdf.VersementJour> versements = caisse.versementsDuJour(d).stream()
                .map(v -> new FacturesPdf.VersementJour(v.patient(), String.valueOf(v.dossier()), v.mode(), v.montant())).toList();
        Map<String, BigDecimal> parMode = new TreeMap<>();
        versements.forEach(x -> parMode.merge(x.mode().isBlank() ? "Non précisé" : x.mode(), x.montant(), BigDecimal::add));
        BigDecimal reste = caisse.jour(d).stream().map(CaisseRepository.Ligne::reste).reduce(BigDecimal.ZERO, BigDecimal::add);
        audit.log("CLOTURE_CAISSE", "cabinet", d);
        return reponse(pdf.cloture(entete(user), d, versements, parMode, reste), "Cloture_caisse_" + d.format(NUMERO));
    }

    // ─────────────────────────────────────────────────────────────────────

    /** En-tête : nom du cabinet, médecin titulaire, mentions légales, identifiant professionnel. */
    private FacturesPdf.Entete entete(MedsolUser user) {
        CabinetService.Cabinet cab = cabinets.current();
        String medecin = jdbc.queryForList("SELECT nom_complet FROM utilisateur WHERE role = 'MEDECIN' ORDER BY cree_le LIMIT 1", String.class)
                .stream().findFirst().map(com.medsol.documents.DocumentsPdf::docteur).orElse(null);
        List<String> mentions = new ArrayList<>();
        String specialite = cabinets.specialtyLabel("fr");
        if (medecin != null && specialite != null) medecin += " — " + specialite;
        if (cab.adresseComplete() != null) mentions.add(cab.adresseComplete());
        if (cab.telephone() != null) mentions.add("Tél. : " + cab.telephone());
        if (cab.numeroOrdre() != null) mentions.add("Inscrit à l'Ordre n° " + cab.numeroOrdre());
        if (cab.maroc() && cab.ligneFiscale() != null) mentions.add(cab.ligneFiscale());
        mentions.addAll(cab.mentions());
        // Maroc : actes médicaux exonérés de TVA (article 91 du Code général des impôts)
        String piedDePage = cab.maroc() ? "Prestations médicales exonérées de TVA (article 91 du Code général des impôts)." : null;
        return new FacturesPdf.Entete(cab.nom(), medecin, mentions, cab.identifiantProfessionnel(), cab.identifiantImprime(),
                cab.devise(), piedDePage);
    }

    private static ResponseEntity<byte[]> reponse(byte[] contenu, String nom) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(nom + ".pdf").build().toString())
                .cacheControl(CacheControl.noStore())
                .body(contenu);
    }

    private static String nom(Patient p) {
        return Normalizer.normalize(p.nom() + "_" + p.prenom(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static ResponseStatusException introuvable() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Introuvable"); }
}
