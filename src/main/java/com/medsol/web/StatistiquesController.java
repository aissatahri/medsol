package com.medsol.web;

import com.medsol.cabinet.CabinetService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.*;

/**
 * Statistiques du cabinet sur une année (reprise de StatistiquesController de SGCM-Cardio) : activité mois par mois,
 * recettes, rendez-vous honorés et absences, actes et médicaments les plus fréquents, patientèle. Les consultations
 * de la corbeille sont exclues. Médecin (/settings… non : page propre, rôle vérifié par la sécurité).
 */
@Controller
public class StatistiquesController {

    public record Mois(String nom, long consultations, long nouveaux, BigDecimal encaisse, int pctConsultations, int pctEncaisse) {}

    public record Ligne(String libelle, long nombre, int pct) {}

    private final JdbcTemplate jdbc;
    private final CabinetService cabinets;

    public StatistiquesController(JdbcTemplate jdbc, CabinetService cabinets) {
        this.jdbc = jdbc;
        this.cabinets = cabinets;
    }

    @GetMapping("/statistiques")
    public String page(@RequestParam(required = false) Integer annee, Model model) {
        CabinetService.Cabinet cab = cabinets.current();
        int a = annee == null ? cab.today().getYear() : Math.max(2000, Math.min(annee, 2100));
        Date du = Date.valueOf(LocalDate.of(a, 1, 1)), au = Date.valueOf(LocalDate.of(a, 12, 31));
        long[] consult = new long[13], nouveaux = new long[13];
        BigDecimal[] encaisse = new BigDecimal[13];
        Arrays.fill(encaisse, BigDecimal.ZERO);
        jdbc.query("SELECT extract(month FROM date_consult)::int, count(*) FROM consultation WHERE supprimee_le IS NULL AND date_consult BETWEEN ? AND ? GROUP BY 1",
                rs -> { consult[rs.getInt(1)] = rs.getLong(2); }, du, au);
        jdbc.query("SELECT extract(month FROM cree_le AT TIME ZONE ?)::int, count(*) FROM patient WHERE NOT archive AND (cree_le AT TIME ZONE ?)::date BETWEEN ? AND ? GROUP BY 1",
                rs -> { nouveaux[rs.getInt(1)] = rs.getLong(2); }, cab.zone().getId(), cab.zone().getId(), du, au);
        jdbc.query("""
                SELECT extract(month FROM v.jour)::int, sum(v.montant) FROM versement v JOIN consultation c ON c.id = v.consultation_id
                WHERE v.annule_le IS NULL AND c.supprimee_le IS NULL AND v.jour BETWEEN ? AND ? GROUP BY 1""",
                rs -> { encaisse[rs.getInt(1)] = rs.getBigDecimal(2); }, du, au);
        long maxC = Arrays.stream(consult).max().orElse(0);
        BigDecimal maxE = Arrays.stream(encaisse).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        Locale l = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        List<Mois> mois = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            mois.add(new Mois(Month.of(m).getDisplayName(TextStyle.SHORT, l), consult[m], nouveaux[m], encaisse[m],
                    maxC == 0 ? 0 : (int) (100 * consult[m] / maxC),
                    maxE.signum() == 0 ? 0 : encaisse[m].multiply(BigDecimal.valueOf(100)).divide(maxE, java.math.RoundingMode.HALF_UP).intValue()));
        }
        Timestamp debut = Timestamp.from(LocalDate.of(a, 1, 1).atStartOfDay(cab.zone()).toInstant());
        Timestamp fin = Timestamp.from(LocalDate.of(a + 1, 1, 1).atStartOfDay(cab.zone()).toInstant());
        Map<String, Long> rdv = new HashMap<>();
        jdbc.query("SELECT statut, count(*) FROM rendez_vous WHERE debut >= ? AND debut < ? GROUP BY statut",
                rs -> { rdv.put(rs.getString(1), rs.getLong(2)); }, debut, fin);
        long totalRdv = rdv.values().stream().mapToLong(Long::longValue).sum();

        model.addAttribute("annee", a);
        model.addAttribute("mois", mois);
        model.addAttribute("totalConsultations", Arrays.stream(consult).sum());
        model.addAttribute("totalNouveaux", Arrays.stream(nouveaux).sum());
        model.addAttribute("totalEncaisse", Arrays.stream(encaisse).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("totalRdv", totalRdv);
        model.addAttribute("tauxAbsence", totalRdv == 0 ? 0 : Math.round(100.0 * rdv.getOrDefault("ABSENT", 0L) / totalRdv));
        model.addAttribute("tauxAnnulation", totalRdv == 0 ? 0 : Math.round(100.0 * rdv.getOrDefault("ANNULE", 0L) / totalRdv));
        model.addAttribute("actes", classement("""
                SELECT a.nom, count(*) FROM consultation_acte ca JOIN acte a ON a.id = ca.acte_id JOIN consultation c ON c.id = ca.consultation_id
                WHERE c.supprimee_le IS NULL AND c.date_consult BETWEEN ? AND ? GROUP BY a.nom ORDER BY 2 DESC LIMIT 8""", du, au));
        model.addAttribute("medicaments", classement("""
                SELECT m.nom, count(*) FROM prescription p JOIN medicament m ON m.id = p.medicament_id JOIN consultation c ON c.id = p.consultation_id
                WHERE c.supprimee_le IS NULL AND c.date_consult BETWEEN ? AND ? GROUP BY m.nom ORDER BY 2 DESC LIMIT 8""", du, au));
        // codes traduits par l'écran (stats.age.* / stats.sex.*)
        model.addAttribute("ages", classement("""
                SELECT CASE WHEN date_naissance IS NULL THEN 'NR'
                            WHEN age(date_naissance) < interval '2 years' THEN 'LT2'
                            WHEN age(date_naissance) < interval '18 years' THEN 'A2_17'
                            WHEN age(date_naissance) < interval '40 years' THEN 'A18_39'
                            WHEN age(date_naissance) < interval '65 years' THEN 'A40_64' ELSE 'A65P' END, count(*)
                FROM patient WHERE NOT archive GROUP BY 1 ORDER BY min(coalesce(date_naissance, '1900-01-01')) DESC"""));
        model.addAttribute("sexes", classement("""
                SELECT CASE sexe WHEN 'F' THEN 'F' WHEN 'M' THEN 'M' ELSE 'NR' END, count(*)
                FROM patient WHERE NOT archive GROUP BY 1 ORDER BY 2 DESC"""));
        model.addAttribute("devise", cab.devise());
        return "statistiques";
    }

    private List<Ligne> classement(String sql, Object... args) {
        List<Object[]> brut = jdbc.query(sql, (rs, i) -> new Object[]{rs.getString(1), rs.getLong(2)}, args);
        long max = brut.stream().mapToLong(o -> (Long) o[1]).max().orElse(0);
        return brut.stream().map(o -> new Ligne((String) o[0], (Long) o[1], max == 0 ? 0 : (int) (100 * (Long) o[1] / max))).toList();
    }
}
