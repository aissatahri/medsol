package com.medsol.settings;

import com.medsol.cabinet.CabinetService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Journal des accès (Paramètres › Journal, repris de SGCM-Cardio) : connexions, dossiers et documents ouverts,
 * modifications — qui, quand, depuis quelle adresse. En base, en ajout seul (RGPD art. 30, HIPAA). Médecin.
 */
@Controller
public class JournalController {

    public record Entree(Instant quand, String utilisateur, String role, String action, String objet, String objetId, String ip) {}

    private static final int LIMITE = 500;

    private final JdbcTemplate jdbc;
    private final CabinetService cabinets;

    public JournalController(JdbcTemplate jdbc, CabinetService cabinets) {
        this.jdbc = jdbc;
        this.cabinets = cabinets;
    }

    @GetMapping("/settings/journal")
    public String page(@RequestParam(required = false) String mois, @RequestParam(defaultValue = "") String q,
                       @RequestParam(required = false) java.util.UUID u,
                       @RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        CabinetService.Cabinet cab = cabinets.current();
        YearMonth m;
        try {
            m = mois == null || mois.isBlank() ? YearMonth.from(cab.today()) : YearMonth.parse(mois);
        } catch (DateTimeParseException e) {
            m = YearMonth.from(cab.today());
        }
        String like = "%" + q.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<Entree> entrees = jdbc.query("""
                SELECT j.quand, coalesce(u.nom_complet, '—'), coalesce(u.role, '-'), j.action, j.objet, j.objet_id, j.ip
                FROM journal_audit j LEFT JOIN utilisateur u ON u.id = j.utilisateur_id
                WHERE j.quand >= ? AND j.quand < ? AND (?::uuid IS NULL OR j.utilisateur_id = ?::uuid)
                  AND (? = '%%' OR lower(coalesce(u.nom_complet, '') || ' ' || j.action || ' ' || j.objet || ' ' || coalesce(j.objet_id, '')) LIKE ?)
                ORDER BY j.quand DESC LIMIT ?""",
                (rs, i) -> new Entree(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)),
                Timestamp.from(m.atDay(1).atStartOfDay(cab.zone()).toInstant()),
                Timestamp.from(m.plusMonths(1).atDay(1).atStartOfDay(cab.zone()).toInstant()), u, u, like, like, LIMITE);
        // historique d'un compte (lien « Historique » de Paramètres › Utilisateurs)
        model.addAttribute("u", u);
        model.addAttribute("compte", u == null ? null : jdbc.query("SELECT nom_complet FROM utilisateur WHERE id = ?",
                (rs, i) -> rs.getString(1), u).stream().findFirst().orElse(null));
        model.addAttribute("mois", m.toString());
        model.addAttribute("q", q.strip());
        model.addAttribute("entrees", entrees);
        model.addAttribute("limite", LIMITE);
        return htmx != null ? "settings/journal :: entrees" : "settings/journal";
    }
}
