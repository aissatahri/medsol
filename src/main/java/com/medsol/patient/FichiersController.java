package com.medsol.patient;

import com.medsol.audit.AuditService;
import com.medsol.security.MedsolUser;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Fichiers joints au dossier (repris de FichiersController de SGCM-Cardio) : scans, résultats, comptes rendus reçus,
 * photos prises au téléphone. Le type est vérifié sur le contenu (PDF, JPEG, PNG, WebP), 10 Mo au plus ; un fichier
 * s'ouvre dans un bac à sable (aucun script). Médecin et assistante.
 */
@Controller
@RequestMapping("/patients/{id}/fichiers")
public class FichiersController {

    public record Fichier(UUID id, String nom, String categorie, String typeMime, int taille, Instant le, LocalDate consultation) {
        public String tailleTexte() { return taille >= 1048576 ? String.format("%.1f Mo", taille / 1048576.0) : Math.max(1, taille / 1024) + " Ko"; }
        public boolean image() { return typeMime.startsWith("image/"); }
    }

    public static final Map<String, String> CATEGORIES = new LinkedHashMap<>();
    static {
        CATEGORIES.put("RESULTAT_ANALYSE", "Résultat d'analyse");
        CATEGORIES.put("COMPTE_RENDU_ANALYSE", "Compte rendu d'analyse");
        CATEGORIES.put("IMAGERIE", "Imagerie");
        CATEGORIES.put("COMPTE_RENDU_IMAGERIE", "Compte rendu d'imagerie");
        CATEGORIES.put("COURRIER", "Courrier");
        CATEGORIES.put("ORDONNANCE", "Ordonnance");
        CATEGORIES.put("AUTRE", "Autre");
    }

    private static final int MAX = 10 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final PatientRepository patients;
    private final AuditService audit;

    private final com.medsol.web.Textes textes;

    public FichiersController(JdbcTemplate jdbc, PatientRepository patients, AuditService audit, com.medsol.web.Textes textes) {
        this.textes = textes;
        this.jdbc = jdbc;
        this.patients = patients;
        this.audit = audit;
    }

    @GetMapping
    public String liste(@PathVariable UUID id, Model model) {
        return carte(id, null, null, model);
    }

    @PostMapping
    public String envoyer(@PathVariable UUID id, @RequestParam("fichier") MultipartFile fichier,
                          @RequestParam(defaultValue = "AUTRE") String categorie, @RequestParam(required = false) UUID consultation,
                          @AuthenticationPrincipal MedsolUser user, Model model) throws IOException {
        patients.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (fichier.isEmpty() || fichier.getSize() > MAX) return carte(id, textes.t("msg.file.tooBig"), null, model);
        byte[] contenu = fichier.getBytes();
        String type = type(contenu);
        if (type == null) return carte(id, textes.t("msg.file.format"), null, model);
        String nom = Optional.ofNullable(fichier.getOriginalFilename()).orElse("document").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (nom.length() > 150) nom = nom.substring(nom.length() - 150);
        UUID f = jdbc.queryForObject("""
                INSERT INTO fichier (cabinet_id, patient_id, consultation_id, nom, categorie, type_mime, taille, contenu, cree_par)
                SELECT current_cabinet(), ?, (SELECT c.id FROM consultation c WHERE c.id = ? AND c.patient_id = ?), ?, ?, ?, ?, ?, ?
                RETURNING id""", UUID.class, id, consultation, id, nom, CATEGORIES.containsKey(categorie) ? categorie : "AUTRE",
                type, contenu.length, contenu, user.id());
        audit.log("FICHIER_AJOUT", "patient", id + "/" + f);
        return carte(id, null, textes.t("msg.file.added", nom), model);
    }

    /** Ouverture : en ligne, dans un bac à sable (CSP sandbox), jamais gardé par le navigateur. */
    @GetMapping("/{f}")
    public ResponseEntity<byte[]> ouvrir(@PathVariable UUID id, @PathVariable UUID f) {
        List<Object[]> l = jdbc.query("SELECT nom, type_mime, contenu FROM fichier WHERE id = ? AND patient_id = ?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getBytes(3)}, f, id);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        audit.log("FICHIER_LECTURE", "patient", id + "/" + f);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType((String) l.get(0)[1]))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename((String) l.get(0)[0]).build().toString())
                .header("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body((byte[]) l.get(0)[2]);
    }

    @PostMapping("/{f}/supprimer")
    public String supprimer(@PathVariable UUID id, @PathVariable UUID f, Model model) {
        boolean ok = jdbc.update("DELETE FROM fichier WHERE id = ? AND patient_id = ?", f, id) == 1;
        if (ok) audit.log("FICHIER_SUPPRESSION", "patient", id + "/" + f);
        return carte(id, null, ok ? textes.t("msg.file.deleted") : null, model);
    }

    private String carte(UUID id, String erreur, String succes, Model model) {
        model.addAttribute("patientId", id);
        model.addAttribute("fichiers", jdbc.query("""
                SELECT f.id, f.nom, f.categorie, f.type_mime, f.taille, f.cree_le, c.date_consult
                FROM fichier f LEFT JOIN consultation c ON c.id = f.consultation_id WHERE f.patient_id = ? ORDER BY f.cree_le DESC""",
                (rs, i) -> new Fichier(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getTimestamp(6).toInstant(), rs.getObject(7, LocalDate.class)), id));
        model.addAttribute("categories", CATEGORIES);
        model.addAttribute("consultations", jdbc.query("SELECT id, date_consult FROM consultation WHERE patient_id = ? AND supprimee_le IS NULL ORDER BY date_consult DESC",
                (rs, i) -> Map.of("id", rs.getObject(1, UUID.class), "date", rs.getObject(2, LocalDate.class)), id));
        model.addAttribute("erreur", erreur);
        model.addAttribute("succes", succes);
        return "patients/fichiers :: fichiers";
    }

    /** Type réel d'après les premiers octets (le nom et le type annoncés ne sont pas crus). */
    static String type(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "application/pdf";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }
}
