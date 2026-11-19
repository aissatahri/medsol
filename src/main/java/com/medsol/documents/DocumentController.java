package com.medsol.documents;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.dossier.ConsultationRepository;
import com.medsol.dossier.ExamenRepository;
import com.medsol.dossier.OrdonnanceRepository;
import com.medsol.patient.Patient;
import com.medsol.patient.PatientRepository;
import com.medsol.security.MedsolUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Documents d'une consultation ouverts dans le navigateur, aucun fichier gardé sur le serveur (repris de
 * DocumentController de SGCM-Cardio). Mêmes textes que les certificats du bureau. Médecin uniquement.
 */
@RestController
@RequestMapping("/consultations/{id}/documents")
public class DocumentController {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final DocumentsPdf pdf;
    private final ConsultationRepository consultations;
    private final PatientRepository patients;
    private final OrdonnanceRepository ordonnances;
    private final ExamenRepository examens;
    private final CabinetService cabinets;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ImpressionReglages reglages;

    public DocumentController(DocumentsPdf pdf, ConsultationRepository consultations, PatientRepository patients,
                              OrdonnanceRepository ordonnances, ExamenRepository examens, CabinetService cabinets,
                              AuditService audit, JdbcTemplate jdbc, ImpressionReglages reglages) {
        this.reglages = reglages;
        this.pdf = pdf;
        this.consultations = consultations;
        this.patients = patients;
        this.ordonnances = ordonnances;
        this.examens = examens;
        this.cabinets = cabinets;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @GetMapping("/ordonnance")
    public ResponseEntity<byte[]> ordonnance(@PathVariable UUID id, @AuthenticationPrincipal MedsolUser user) {
        ConsultationRepository.Consultation c = consultation(id);
        Patient p = patient(c);
        byte[] contenu = pdf.ordonnance(cadre(user, "ORDONNANCE"), p.nom(), p.prenom(), precisions(p, c), c.date(), ordonnances.lignes(id), prochainRdv(c));
        audit.log("IMPRESSION_ORDONNANCE", "consultation", id);
        return reponse(contenu, "ordonnance", p, c.date());
    }

    @GetMapping("/{genre:analyse|imagerie}")
    public ResponseEntity<byte[]> demande(@PathVariable UUID id, @PathVariable String genre, @AuthenticationPrincipal MedsolUser user) {
        ExamenRepository.Genre g = ExamenRepository.Genre.depuis(genre);
        ConsultationRepository.Consultation c = consultation(id);
        Patient p = patient(c);
        byte[] contenu = pdf.demande(cadre(user, g == ExamenRepository.Genre.ANALYSE ? "ANALYSE" : "IMAGERIE"), g, p.nom(), p.prenom(), c.date(), examens.liste(g, id));
        audit.log("IMPRESSION_DEMANDE_" + g.name(), "consultation", id);
        return reponse(contenu, "demande_" + genre, p, c.date());
    }

    /** Certificats du bureau : consultation, aptitude, chronique, arret-travail, arret-scolaire (avec dates). */
    @GetMapping("/certificat")
    public ResponseEntity<byte[]> certificat(@PathVariable UUID id, @RequestParam String type,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate debut,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fin,
                                             @AuthenticationPrincipal MedsolUser user) {
        ConsultationRepository.Consultation c = consultation(id);
        Patient patient = patient(c);
        LocalDate aujourdhui = cabinets.current().today();
        String dr = "Je soussigné " + DocumentsPdf.docteur(user.fullName());
        String qui = designation(patient) + " " + patient.nom() + " " + patient.prenom();
        String demande = "\n\nCertificat établi à la demande de l'intéressé(e) pour faire valoir ce que de droit.";
        String titre;
        String texte;
        switch (type) {
            case "consultation" -> {
                titre = "Certificat Médical de Consultation";
                texte = dr + ", certifie avoir examiné " + qui + " le " + c.date().format(JOUR) + ".";
            }
            case "aptitude" -> {
                titre = "Certificat d'Aptitude Sportive";
                texte = dr + ", certifie que " + qui
                        + (patient.dateNaissance() != null ? ", né(e) le " + patient.dateNaissance().format(JOUR) : "")
                        + ", ne présente aucune contre-indication à la pratique du sport."
                        + "\n\nCertificat établi le " + aujourdhui.format(JOUR) + " à la demande de l'intéressé(e) pour faire valoir ce que de droit.";
            }
            case "chronique" -> {
                titre = "Certificat Maladie Chronique";
                texte = dr + ", certifie que " + qui
                        + " est atteint(e) d'une affection de longue durée nécessitant un traitement prolongé et une thérapeutique particulièrement coûteuse."
                        + "\n\nCe certificat est établi conformément aux dispositions réglementaires en vigueur."
                        + "\n\nCertificat établi le " + aujourdhui.format(JOUR) + " à la demande de l'intéressé(e) pour faire valoir ce que de droit.";
            }
            case "arret-travail", "arret-scolaire" -> {
                if (debut == null || fin == null || fin.isBefore(debut)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dates de l'arrêt incorrectes");
                }
                boolean travail = type.equals("arret-travail");
                titre = travail ? "Certificat d'Arrêt de Travail" : "Certificat d'Arrêt Scolaire";
                texte = dr + ", certifie que l'état de santé de " + qui + " nécessite un arrêt " + (travail ? "de travail" : "scolaire")
                        + "\ndu " + debut.format(JOUR) + " au " + fin.format(JOUR) + " inclus."
                        + (travail ? "\n\nSauf complication, la reprise du travail pourra avoir lieu le " + fin.plusDays(1).format(JOUR) + "." : "")
                        + demande;
            }
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        audit.log("IMPRESSION_CERTIFICAT", "consultation", id);
        return reponse(pdf.certificat(cadre(user, "CERTIFICAT"), titre, texte, DocumentsPdf.docteur(user.fullName()), aujourdhui), "certificat_" + type, patient, aujourdhui);
    }

    // ─────────────────────────────────────────────────────────────────────

    /** En-tête du médecin (sauf papier à en-tête) et ville du « Fait à … ». Documents en français. */
    DocumentsPdf.Cadre cadre(MedsolUser user, String document) {
        return cadre(cabinets, DocumentsPdf.docteur(user.fullName()), reglages.pour(document));
    }

    public static DocumentsPdf.Cadre cadre(CabinetService cabinets, String medecin, MiseEnPage mise) {
        CabinetService.Cabinet cab = cabinets.current();
        DocumentsPdf.EnTete e = cab.papierEntete() ? null : new DocumentsPdf.EnTete(medecin, cabinets.specialtyLabel("fr"),
                cab.numeroOrdre(), cab.identifiantImprime(), cab.nom(), cab.adresseComplete(), cab.telephone());
        return new DocumentsPdf.Cadre(e, cab.villeDocuments(), mise);
    }

    /** « 45 ans », « 1 an 3 mois », « 8 mois » ; pour l'enfant de moins de 15 ans, le dernier poids des 90 jours. */
    private String precisions(Patient p, ConsultationRepository.Consultation c) {
        if (p.dateNaissance() == null) return null;
        Period age = Period.between(p.dateNaissance(), c.date());
        if (age.isNegative()) return null;
        String texte;
        if (age.getYears() >= 2) texte = age.getYears() + " ans";
        else if (age.getYears() == 1) texte = "1 an" + (age.getMonths() > 0 ? " " + age.getMonths() + " mois" : "");
        else texte = age.getMonths() > 0 ? age.getMonths() + " mois" : age.getDays() + " jour" + (age.getDays() > 1 ? "s" : "");
        if (age.getYears() < 15) {
            String poids = jdbc.query("""
                    SELECT cc.valeur FROM consultation_constante cc
                    JOIN consultation k ON k.id = cc.consultation_id JOIN type_constante t ON t.id = cc.type_id
                    WHERE k.patient_id = ? AND t.nom = 'POIDS' AND k.supprimee_le IS NULL AND cc.valeur <> ''
                      AND k.date_consult BETWEEN ?::date - 90 AND ?::date
                    ORDER BY k.date_consult DESC LIMIT 1""", (rs, i) -> rs.getString(1), p.id(), c.date(), c.date())
                    .stream().findFirst().orElse(null);
            if (poids != null) texte += "  ·  " + poids.strip() + " kg";
        }
        return texte;
    }

    /** Premier rendez-vous du patient après la consultation, affiché dans le cadre de l'ordonnance. */
    private DocumentsPdf.ProchainRdv prochainRdv(ConsultationRepository.Consultation c) {
        var zone = cabinets.current().zone();
        return jdbc.query("""
                        SELECT debut FROM rendez_vous WHERE patient_id = ? AND debut >= ? AND statut NOT IN ('ANNULE','ABSENT')
                        ORDER BY debut LIMIT 1""",
                        (rs, i) -> {
                            ZonedDateTime d = rs.getTimestamp(1).toInstant().atZone(zone);
                            return new DocumentsPdf.ProchainRdv(d.toLocalDate(), d.toLocalTime());
                        }, c.patientId(), java.sql.Timestamp.from(c.date().plusDays(1).atStartOfDay(zone).toInstant()))
                .stream().findFirst().orElse(null);
    }

    /** « Monsieur » / « Madame », ou « le jeune garçon » / « la jeune fille » avant 18 ans (comme le bureau). */
    static String designation(Patient p) {
        boolean femme = "F".equals(p.sexe());
        Integer age = p.age();
        if (age != null && age < 18) return femme ? "la jeune fille" : "le jeune garçon";
        return femme ? "Madame" : "Monsieur";
    }

    private ConsultationRepository.Consultation consultation(UUID id) {
        return consultations.trouver(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Patient patient(ConsultationRepository.Consultation c) {
        return patients.find(c.patientId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static ResponseEntity<byte[]> reponse(byte[] contenu, String nature, Patient p, LocalDate date) {
        String fichier = nature + "_" + sansAccents(p.nom() + "_" + p.prenom()) + "_" + date.format(DateTimeFormatter.ISO_DATE) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(fichier).build().toString())
                // Données de santé : jamais gardées par le navigateur ni par un proxy
                .cacheControl(CacheControl.noStore())
                .body(contenu);
    }

    private static String sansAccents(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
