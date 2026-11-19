package com.medsol.dossier;

import com.medsol.audit.AuditService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Menu « Consultations » (repris de SGCM-Cardio Web, consultation/recherche) : historique de toutes les consultations
 * du cabinet, filtré par texte (patient, motif, champs cliniques, médicaments prescrits) et par période.
 * Médecin uniquement (/consultations/** dans SecurityConfig). Les consultations de la corbeille sont exclues.
 */
@Controller
public class HistoriqueConsultationsController {

    public record Ligne(UUID id, UUID patientId, LocalDate date, String nom, String prenom, Long numero, String motif, String diagnostic) {
        public String nomComplet() { return ((nom == null ? "" : nom.toUpperCase()) + " " + (prenom == null ? "" : prenom)).trim(); }
    }

    static final Set<String> PORTEES = Set.of("TOUT", "PATIENT", "CLINIQUE", "MEDICAMENT");
    static final int PAGE = 50;

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public HistoriqueConsultationsController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/consultations")
    public String liste(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "TOUT") String dans,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        String texte = q.strip();
        String portee = PORTEES.contains(dans) ? dans : "TOUT";
        int p = Math.max(0, page);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("c.supprimee_le IS NULL");
        if (du != null) { where.append(" AND c.date_consult >= ?"); args.add(du); }
        if (au != null) { where.append(" AND c.date_consult <= ?"); args.add(au); }
        if (!texte.isEmpty()) {
            String like = "%" + texte.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            List<String> ou = new ArrayList<>();
            if (portee.equals("TOUT") || portee.equals("PATIENT")) {
                ou.add("lower(p.nom || ' ' || p.prenom || ' ' || p.prenom || ' ' || p.nom || ' ' || coalesce(p.numero::text, '') || ' ' || coalesce(p.telephone, '')) LIKE ?");
                args.add(like);
            }
            if (portee.equals("TOUT") || portee.equals("CLINIQUE")) {
                ou.add("lower(coalesce(c.motif, '')) LIKE ?");
                ou.add("EXISTS (SELECT 1 FROM consultation_champ ch WHERE ch.consultation_id = c.id AND lower(ch.valeur) LIKE ?)");
                args.add(like);
                args.add(like);
            }
            if (portee.equals("TOUT") || portee.equals("MEDICAMENT")) {
                ou.add("""
                        EXISTS (SELECT 1 FROM prescription pr JOIN medicament m ON m.id = pr.medicament_id
                                WHERE pr.consultation_id = c.id AND lower(m.nom || ' ' || coalesce(m.dci, '')) LIKE ?)""");
                args.add(like);
            }
            where.append(" AND (").append(String.join(" OR ", ou)).append(")");
        }
        args.add(PAGE + 1);
        args.add(p * PAGE);
        // motif : celui de la consultation, sinon le champ « symptômes » ; diagnostic : le champ « diagnostic médical »
        List<Ligne> lignes = jdbc.query("""
                SELECT c.id, c.patient_id, c.date_consult, p.nom, p.prenom, p.numero,
                       coalesce(nullif(c.motif, ''), (SELECT ch.valeur FROM consultation_champ ch JOIN type_champ_clinique t ON t.id = ch.type_id
                                                       WHERE ch.consultation_id = c.id AND t.nom = 'SYMPTOMES')),
                       (SELECT ch.valeur FROM consultation_champ ch JOIN type_champ_clinique t ON t.id = ch.type_id
                        WHERE ch.consultation_id = c.id AND t.nom = 'DIAGNOSTIC_MEDICAL')
                FROM consultation c JOIN patient p ON p.id = c.patient_id
                WHERE
                """ + where + """

                ORDER BY c.date_consult DESC, c.cree_le DESC LIMIT ? OFFSET ?""",
                (rs, i) -> new Ligne(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, LocalDate.class),
                        rs.getString(4), rs.getString(5), (Long) rs.getObject(6), rs.getString(7), rs.getString(8)),
                args.toArray());
        boolean suivante = lignes.size() > PAGE;
        if (!texte.isEmpty()) audit.log("RECHERCHE", "consultation", portee);
        model.addAttribute("resultats", suivante ? lignes.subList(0, PAGE) : lignes);
        model.addAttribute("suivante", suivante);
        model.addAttribute("page", p);
        model.addAttribute("q", texte);
        model.addAttribute("dans", portee);
        model.addAttribute("du", du);
        model.addAttribute("au", au);
        return htmx != null ? "consultations/liste :: resultats" : "consultations/liste";
    }
}
