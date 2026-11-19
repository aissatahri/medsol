package com.medsol.caisse;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;

/**
 * Export de la caisse pour Excel (repris d'ExportController de SGCM-Cardio) : CSV séparé par des points-virgules,
 * virgule décimale, marque UTF-8 (Excel ouvre le fichier directement avec les accents). Période d'un an au plus.
 */
@RestController
public class ExportController {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FICHIER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbc;
    private final CabinetService cabinets;
    private final AuditService audit;

    public ExportController(JdbcTemplate jdbc, CabinetService cabinets, AuditService audit) {
        this.jdbc = jdbc;
        this.cabinets = cabinets;
        this.audit = audit;
    }

    /** Consultations de la période : actes, total dû, versé, reste et modes de paiement. */
    @GetMapping("/caisse/export/consultations")
    public ResponseEntity<byte[]> consultations(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au) {
        LocalDate[] p = periode(du, au);
        String s = Monnaie.symbole(cabinets.current().devise());
        StringBuilder csv = new StringBuilder();
        ligne(csv, "Date consultation", "N° dossier", "Nom", "Prénom", "Actes", "Total dû (" + s + ")", "Versé (" + s + ")", "Reste (" + s + ")", "Modes de paiement");
        BigDecimal[] totaux = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        jdbc.query("""
                SELECT c.date_consult, p.numero, p.nom, p.prenom,
                       (SELECT string_agg(a.nom || CASE WHEN ca.paye THEN '' ELSE ' (offert)' END, ', ' ORDER BY a.nom)
                        FROM consultation_acte ca JOIN acte a ON a.id = ca.acte_id WHERE ca.consultation_id = c.id) AS actes,
                       (SELECT coalesce(sum(ca.prix), 0) FROM consultation_acte ca WHERE ca.consultation_id = c.id AND ca.paye) AS total,
                       (SELECT coalesce(sum(v.montant), 0) FROM versement v WHERE v.consultation_id = c.id AND v.annule_le IS NULL) AS verse,
                       (SELECT string_agg(DISTINCT v.mode, ', ') FROM versement v WHERE v.consultation_id = c.id AND v.annule_le IS NULL) AS modes
                FROM consultation c JOIN patient p ON p.id = c.patient_id
                WHERE c.supprimee_le IS NULL AND c.date_consult BETWEEN ? AND ?
                ORDER BY c.date_consult, p.nom""", rs -> {
            BigDecimal total = rs.getBigDecimal("total");
            BigDecimal verse = rs.getBigDecimal("verse");
            BigDecimal reste = total.subtract(verse).max(BigDecimal.ZERO);
            totaux[0] = totaux[0].add(total);
            totaux[1] = totaux[1].add(verse);
            totaux[2] = totaux[2].add(reste);
            ligne(csv, rs.getObject(1, LocalDate.class).format(JOUR), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString("actes"), montant(total), montant(verse), montant(reste), modes(rs.getString("modes")));
        }, Date.valueOf(p[0]), Date.valueOf(p[1]));
        ligne(csv, "TOTAL", "", "", "", "", montant(totaux[0]), montant(totaux[1]), montant(totaux[2]), "");
        audit.log("EXPORT_CAISSE_CONSULTATIONS", "cabinet", p[0] + "/" + p[1]);
        return fichier(csv, "consultations", p);
    }

    /** Encaissements de la période, puis totaux par mode. */
    @GetMapping("/caisse/export/versements")
    public ResponseEntity<byte[]> versements(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au) {
        LocalDate[] p = periode(du, au);
        String s = Monnaie.symbole(cabinets.current().devise());
        StringBuilder csv = new StringBuilder();
        ligne(csv, "Date encaissement", "N° reçu", "N° dossier", "Nom", "Prénom", "Consultation du", "Mode", "Montant (" + s + ")");
        Map<String, BigDecimal> parMode = new TreeMap<>();
        jdbc.query("""
                SELECT v.jour, v.numero, p.numero, p.nom, p.prenom, c.date_consult, v.mode, v.montant
                FROM versement v JOIN consultation c ON c.id = v.consultation_id JOIN patient p ON p.id = c.patient_id
                WHERE v.annule_le IS NULL AND v.jour BETWEEN ? AND ? ORDER BY v.jour, v.cree_le""", rs -> {
            String mode = CaisseRepository.modeLibelle(rs.getString(7));
            parMode.merge(mode, rs.getBigDecimal(8), BigDecimal::add);
            ligne(csv, rs.getObject(1, LocalDate.class).format(JOUR), "R-" + rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getObject(6, LocalDate.class).format(JOUR), mode, montant(rs.getBigDecimal(8)));
        }, Date.valueOf(p[0]), Date.valueOf(p[1]));
        csv.append("\r\n");
        parMode.forEach((mode, total) -> ligne(csv, "TOTAL " + mode.toUpperCase(), "", "", "", "", "", "", montant(total)));
        ligne(csv, "TOTAL ENCAISSÉ", "", "", "", "", "", "", montant(parMode.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
        audit.log("EXPORT_CAISSE_VERSEMENTS", "cabinet", p[0] + "/" + p[1]);
        return fichier(csv, "versements", p);
    }

    private LocalDate[] periode(LocalDate du, LocalDate au) {
        LocalDate aujourdhui = cabinets.current().today();
        LocalDate d = du == null ? aujourdhui.withDayOfMonth(1) : du;
        LocalDate f = au == null ? aujourdhui : au;
        if (f.isBefore(d) || ChronoUnit.DAYS.between(d, f) > 366) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Période invalide (un an au plus)");
        }
        return new LocalDate[]{d, f};
    }

    private static String modes(String codes) {
        if (codes == null) return "";
        StringBuilder b = new StringBuilder();
        for (String c : codes.split(", ")) b.append(b.isEmpty() ? "" : ", ").append(CaisseRepository.modeLibelle(c));
        return b.toString();
    }

    private static String montant(BigDecimal v) { return Monnaie.arrondi(v).toPlainString().replace('.', ','); }

    private static ResponseEntity<byte[]> fichier(StringBuilder csv, String nature, LocalDate[] p) {
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        sortie.writeBytes(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});   // marque UTF-8 : Excel lit les accents
        sortie.writeBytes(csv.toString().getBytes(StandardCharsets.UTF_8));
        String nom = nature + "_" + p[0].format(FICHIER) + "_au_" + p[1].format(FICHIER) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nom).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(sortie.toByteArray());
    }

    /** Champ CSV : entre guillemets si besoin ; une formule (=, +, -, @) est neutralisée (injection dans Excel). */
    private static void ligne(StringBuilder csv, String... valeurs) {
        for (int i = 0; i < valeurs.length; i++) {
            if (i > 0) csv.append(';');
            String v = valeurs[i] == null ? "" : valeurs[i];
            if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?[0-9 ,]+")) v = "'" + v;
            if (v.contains(";") || v.contains("\"") || v.contains("\n")) v = "\"" + v.replace("\"", "\"\"") + "\"";
            csv.append(v);
        }
        csv.append("\r\n");
    }
}
