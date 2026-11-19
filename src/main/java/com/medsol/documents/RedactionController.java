package com.medsol.documents;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.dossier.Catalogues;
import com.medsol.dossier.ConsultationRepository;
import com.medsol.dossier.OrdonnanceRepository;
import com.medsol.patient.Patient;
import com.medsol.patient.PatientRepository;
import com.medsol.security.MedsolUser;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Rédaction d'une lettre de référence ou d'un compte rendu (repris de RedactionController de SGCM-Cardio) :
 * prérempli depuis la consultation ou la trame de la spécialité, enregistré, imprimé en PDF. Médecin.
 */
@Controller
@RequestMapping("/consultations/{c}/redaction/{modele}")
public class RedactionController {

    private final ConsultationRepository consultations;
    private final OrdonnanceRepository ordonnances;
    private final PatientRepository patients;
    private final Catalogues catalogues;
    private final CabinetService cabinets;
    private final DocumentsPdf pdf;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ImpressionReglages reglages;

    public RedactionController(ConsultationRepository consultations, OrdonnanceRepository ordonnances, PatientRepository patients,
                               Catalogues catalogues, CabinetService cabinets, DocumentsPdf pdf, AuditService audit, JdbcTemplate jdbc,
                               ImpressionReglages reglages) {
        this.consultations = consultations;
        this.ordonnances = ordonnances;
        this.patients = patients;
        this.catalogues = catalogues;
        this.cabinets = cabinets;
        this.pdf = pdf;
        this.audit = audit;
        this.jdbc = jdbc;
        this.reglages = reglages;
    }

    @GetMapping
    public String formulaire(@PathVariable UUID c, @PathVariable String modele, Model model) {
        ConsultationRepository.Consultation cons = consultation(c);
        Redactions.Modele m = modele(modele);
        Patient p = patients.find(cons.patientId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<String[]> enregistree = jdbc.query("SELECT destinataire, contenu, conclusion FROM redaction WHERE consultation_id = ? AND modele = ?",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, c, m.code());
        model.addAttribute("c", cons);
        model.addAttribute("patient", p);
        model.addAttribute("m", m);
        model.addAttribute("destinataire", enregistree.isEmpty() ? "" : enregistree.get(0)[0]);
        model.addAttribute("contenu", enregistree.isEmpty() ? prerempli(cons, m) : enregistree.get(0)[1]);
        model.addAttribute("conclusion", enregistree.isEmpty() ? "" : enregistree.get(0)[2]);
        return "documents/redaction";
    }

    /** Enregistre, puis imprime si demandé (le PDF s'ouvre dans un nouvel onglet). */
    @PostMapping
    public Object enregistrer(@PathVariable UUID c, @PathVariable String modele, @RequestParam(defaultValue = "") String destinataire,
                              @RequestParam String contenu, @RequestParam(defaultValue = "") String conclusion,
                              @RequestParam(defaultValue = "false") boolean imprimer, @AuthenticationPrincipal MedsolUser user) {
        ConsultationRepository.Consultation cons = consultation(c);
        Redactions.Modele m = modele(modele);
        String texte = contenu.length() > 20000 ? contenu.substring(0, 20000) : contenu;
        jdbc.update("""
                INSERT INTO redaction (cabinet_id, consultation_id, modele, destinataire, contenu, conclusion)
                VALUES (current_cabinet(), ?, ?, ?, ?, ?)
                ON CONFLICT (consultation_id, modele) DO UPDATE SET destinataire = excluded.destinataire, contenu = excluded.contenu,
                    conclusion = excluded.conclusion, modifie_le = now()""",
                c, m.code(), destinataire.strip(), texte, conclusion.strip());
        audit.log("REDACTION_" + m.code(), "consultation", c);
        if (!imprimer) return "redirect:/consultations/" + c + "/redaction/" + m.code() + "?enregistre";
        Patient p = patients.find(cons.patientId()).orElseThrow();
        CabinetService.Cabinet cab = cabinets.current();
        String signature = DocumentsPdf.docteur(user.fullName());
        DocumentsPdf.Cadre cadre = DocumentController.cadre(cabinets, signature,
                reglages.pour(m == Redactions.LETTRE ? "LETTRE" : "COMPTE_RENDU"));
        byte[] contenuPdf = m == Redactions.LETTRE
                ? pdf.lettre(cadre, cadre.ville() == null ? cab.nom() : cadre.ville(),
                cab.today(), destinataire.strip(), p.nomComplet(), p.dateNaissance(), texte, signature)
                : pdf.compteRendu(cadre, m.titre(), m.sousTitre(), cons.date(), p.nomComplet(), p.dateNaissance(), p.age(), texte, conclusion, signature);
        audit.log("IMPRESSION_" + m.code(), "consultation", c);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(m.code().toLowerCase() + "_" + p.numero() + ".pdf").build().toString())
                .cacheControl(CacheControl.noStore()).body(contenuPdf);
    }

    /** Lettre : motif, examen, diagnostic et traitement de la consultation ; compte rendu : trame de la spécialité. */
    private String prerempli(ConsultationRepository.Consultation cons, Redactions.Modele m) {
        if (m != Redactions.LETTRE) return m.trame();
        ConsultationRepository.Saisie s = consultations.lire(cons.id());
        StringBuilder b = new StringBuilder("Cher confrère,\n\nJe vous adresse ce patient, vu en consultation le "
                + cons.date().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ".");
        for (Catalogues.TypeChamp t : catalogues.champs()) {
            String v = s.champs().get(t.id());
            if (v != null) b.append("\n\n").append(t.label()).append(" : ").append(v);
        }
        String traitement = ordonnances.lignes(cons.id()).stream().map(l -> l.medicament() + (l.forme() == null ? "" : " " + l.forme())
                + (l.details().isEmpty() ? "" : " (" + l.details() + ")")).collect(Collectors.joining(", "));
        if (!traitement.isEmpty()) b.append("\n\nTraitement : ").append(traitement).append('.');
        b.append("\n\nJe vous remercie de bien vouloir le prendre en charge et vous prie d'agréer, cher confrère, l'expression de mes salutations distinguées.");
        return b.toString();
    }

    private ConsultationRepository.Consultation consultation(UUID c) {
        return consultations.trouver(c).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Redactions.Modele modele(String code) {
        return Redactions.trouver(cabinets.current().specialite(), code).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
