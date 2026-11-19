package com.medsol.documents;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.dossier.AllergiesRepository;
import com.medsol.dossier.Catalogues;
import com.medsol.dossier.ConsultationRepository;
import com.medsol.dossier.ExamenRepository;
import com.medsol.dossier.OrdonnanceRepository;
import com.medsol.patient.Patient;
import com.medsol.patient.PatientRepository;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fiche de soins du patient (reprise de FicheSoinsService de SGCM-Cardio), généralisée aux spécialités : chaque
 * consultation montre ses constantes et ses champs cliniques tels que paramétrés pour le cabinet. Médecin.
 */
@RestController
public class FicheSoinsController {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PatientRepository patients;
    private final ConsultationRepository consultations;
    private final OrdonnanceRepository ordonnances;
    private final ExamenRepository examens;
    private final AllergiesRepository allergies;
    private final Catalogues catalogues;
    private final CabinetService cabinets;
    private final DocumentsPdf pdf;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public FicheSoinsController(PatientRepository patients, ConsultationRepository consultations, OrdonnanceRepository ordonnances,
                                ExamenRepository examens, AllergiesRepository allergies, Catalogues catalogues, CabinetService cabinets,
                                DocumentsPdf pdf, AuditService audit, JdbcTemplate jdbc) {
        this.patients = patients;
        this.consultations = consultations;
        this.ordonnances = ordonnances;
        this.examens = examens;
        this.allergies = allergies;
        this.catalogues = catalogues;
        this.cabinets = cabinets;
        this.pdf = pdf;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /** Fiche de toutes les consultations, ou jusqu'à celle indiquée (?jusqua=). */
    @GetMapping("/patients/{id}/dossier/fiche-soins")
    public ResponseEntity<byte[]> fiche(@PathVariable UUID id, @RequestParam(required = false) UUID jusqua) {
        Patient p = patients.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        CabinetService.Cabinet cab = cabinets.current();
        List<ConsultationRepository.Visite> visites = new ArrayList<>(consultations.visites(id));
        Collections.reverse(visites);   // de la plus ancienne à la plus récente
        if (jusqua != null) {
            int fin = -1;
            for (int i = 0; i < visites.size(); i++) if (visites.get(i).id().equals(jusqua)) fin = i;
            if (fin < 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            visites = visites.subList(0, fin + 1);
        }
        List<Catalogues.TypeChamp> champs = catalogues.champs();
        List<Catalogues.TypeConstante> constantes = catalogues.constantes();

        List<FicheSoins.Champ> identite = new ArrayList<>(List.of(
                new FicheSoins.Champ("Nom et prénom :", p.nomComplet()),
                new FicheSoins.Champ("N° dossier :", String.valueOf(p.numero())),
                new FicheSoins.Champ("Âge :", p.age() == null ? "" : p.age() + " ans"),
                new FicheSoins.Champ("Sexe :", p.sexe() == null ? "" : "F".equals(p.sexe()) ? "Féminin" : "Masculin"),
                new FicheSoins.Champ("Téléphone :", p.telephone()),
                new FicheSoins.Champ("Couverture :", p.couverture())));
        String antecedents = allergies.antecedents(id).stream().map(a -> a.description() + (a.note() == null ? "" : " (" + a.note() + ")"))
                .collect(Collectors.joining(" ; "));
        String allergiesTexte = allergies.liste(id).stream().map(AllergiesRepository.Allergie::nom).collect(Collectors.joining(", "));
        if (!allergiesTexte.isEmpty()) antecedents = (antecedents.isEmpty() ? "" : antecedents + "\n") + "Allergies : " + allergiesTexte;

        List<FicheSoins.Bloc> blocs = new ArrayList<>();
        for (ConsultationRepository.Visite v : visites) {
            ConsultationRepository.Saisie s = consultations.lire(v.id());
            List<FicheSoins.Section> sections = new ArrayList<>();
            List<FicheSoins.Champ> grille = new ArrayList<>();
            for (Catalogues.TypeConstante t : constantes) {
                if (!t.complexe() && s.simples().get(t.id()) != null) {
                    grille.add(new FicheSoins.Champ(t.label() + " :", s.simples().get(t.id()) + (t.unite() == null ? "" : " " + t.unite())));
                }
                if (t.complexe() && s.complexes().get(t.id()) != null) {
                    for (Catalogues.Cle k : t.cles()) {
                        String val = s.complexes().get(t.id()).get(k.cle());
                        if (val != null) grille.add(new FicheSoins.Champ(k.label() + " :", val + (k.unite() == null ? "" : " " + k.unite())));
                    }
                }
            }
            if (!grille.isEmpty()) sections.add(new FicheSoins.Section("Constantes", null, true, grille));
            for (Catalogues.TypeChamp t : champs) {
                String val = s.champs().get(t.id());
                if (val != null) sections.add(new FicheSoins.Section(t.label() + " :", val, "EXAMEN_CLINIQUE".equals(t.nom()), List.of()));
            }
            String traitement = ordonnances.lignes(v.id()).stream()
                    .map(l -> "- " + l.medicament() + (l.forme() == null ? "" : " " + l.forme()) + (l.details().isEmpty() ? "" : " : " + l.details()))
                    .collect(Collectors.joining("\n"));
            if (!traitement.isEmpty()) sections.add(new FicheSoins.Section("Traitement :", traitement, false, List.of()));
            String demandes = java.util.stream.Stream.of(ExamenRepository.Genre.values()).flatMap(g -> examens.liste(g, v.id()).stream())
                    .map(e -> e.intitule() + (e.aResultat() ? " : " + e.resultat() : "")).collect(Collectors.joining("\n"));
            if (!demandes.isEmpty()) sections.add(new FicheSoins.Section("Examens demandés :", demandes, false, List.of()));
            prochainRdv(id, v.date(), cab).ifPresent(r -> sections.add(new FicheSoins.Section("Prochain rendez-vous :", r, false, List.of())));
            blocs.add(new FicheSoins.Bloc("Consultation du " + v.date().format(JOUR), sections));
        }
        FicheSoins fiche = new FicheSoins(cab.today().format(JOUR), visites.isEmpty() ? "" : visites.get(0).date().format(JOUR), identite,
                visites.isEmpty() || visites.get(0).motif() == null ? "" : visites.get(0).motif(), antecedents,
                p.traitementAvant() == null ? "" : p.traitementAvant(), blocs);
        audit.log("IMPRESSION_FICHE_SOINS", "patient", id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("fiche_soins_" + p.numero() + ".pdf").build().toString())
                .cacheControl(CacheControl.noStore()).body(pdf.ficheSoins(fiche));
    }

    private Optional<String> prochainRdv(UUID patient, LocalDate apres, CabinetService.Cabinet cab) {
        return jdbc.query("SELECT debut FROM rendez_vous WHERE patient_id = ? AND debut >= ? AND statut NOT IN ('ANNULE','ABSENT') ORDER BY debut LIMIT 1",
                (rs, i) -> {
                    ZonedDateTime d = rs.getTimestamp(1).toInstant().atZone(cab.zone());
                    return d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm"));
                }, patient, Timestamp.from(apres.plusDays(1).atStartOfDay(cab.zone()).toInstant())).stream().findFirst();
    }
}
