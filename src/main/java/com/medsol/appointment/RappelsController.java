package com.medsol.appointment;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.security.MedsolUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Rappels des rendez-vous (repris de RappelsController de SGCM-Cardio) : liste des rendez-vous d'un jour (demain par
 * défaut), lien WhatsApp avec le message prérempli (numéro mis au format international du pays du cabinet), appel ;
 * chaque rappel fait est noté pour ne pas rappeler deux fois. L'envoi automatique de SMS demandera un prestataire.
 */
@Controller
@RequestMapping("/planning/rappels")
public class RappelsController {

    public record Rappel(UUID rdvId, String heure, String patient, String telephone, String motif, String whatsapp,
                         String appel, String dejaRappele) {}

    private static final Map<String, String> INDICATIFS = Map.ofEntries(
            Map.entry("MA", "212"), Map.entry("DZ", "213"), Map.entry("TN", "216"), Map.entry("SN", "221"), Map.entry("CI", "225"),
            Map.entry("ML", "223"), Map.entry("BF", "226"), Map.entry("CM", "237"), Map.entry("GA", "241"), Map.entry("CD", "243"),
            Map.entry("FR", "33"), Map.entry("BE", "32"), Map.entry("CH", "41"), Map.entry("ES", "34"), Map.entry("DE", "49"),
            Map.entry("GB", "44"), Map.entry("US", "1"), Map.entry("CA", "1"));
    private static final Set<String> CANAUX = Set.of("WHATSAPP", "SMS", "APPEL");

    private final JdbcTemplate jdbc;
    private final CabinetService cabinets;
    private final AuditService audit;

    private final com.medsol.web.Textes textes;

    public RappelsController(JdbcTemplate jdbc, CabinetService cabinets, AuditService audit, com.medsol.web.Textes textes) {
        this.textes = textes;
        this.jdbc = jdbc;
        this.cabinets = cabinets;
        this.audit = audit;
    }

    @GetMapping
    public String page(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate jour, Model model) {
        CabinetService.Cabinet cab = cabinets.current();
        LocalDate j = jour == null ? cab.today().plusDays(1) : jour;
        String jourTexte = j.format(DateTimeFormatter.ofPattern("EEEE d MMMM", org.springframework.context.i18n.LocaleContextHolder.getLocale()));
        List<Rappel> rappels = jdbc.query("""
                SELECT r.id, r.debut, p.prenom, upper(p.nom) || ' ' || p.prenom, p.telephone, r.motif,
                       (SELECT string_agg(x.canal || ' ' || to_char(x.envoye_le, 'DD/MM HH24:MI'), ', ') FROM rdv_rappel x WHERE x.rdv_id = r.id)
                FROM rendez_vous r JOIN patient p ON p.id = r.patient_id
                WHERE r.debut >= ? AND r.debut < ? AND r.statut IN ('PLANIFIE','CONFIRME','REPORTE') ORDER BY r.debut""",
                (rs, i) -> {
                    ZonedDateTime d = rs.getTimestamp(2).toInstant().atZone(cab.zone());
                    String heure = d.format(DateTimeFormatter.ofPattern("HH:mm"));
                    String international = international(rs.getString(5), cab.pays());
                    String message = textes.t("msg.reminder.text", rs.getString(3), cab.nom(), jourTexte, heure);
                    return new Rappel(rs.getObject(1, UUID.class), heure, rs.getString(4), rs.getString(5), rs.getString(6),
                            international == null ? null : "https://wa.me/" + international + "?text=" + URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20"),
                            international == null ? null : "tel:+" + international, rs.getString(7));
                },
                Timestamp.from(j.atStartOfDay(cab.zone()).toInstant()), Timestamp.from(j.plusDays(1).atStartOfDay(cab.zone()).toInstant()));
        model.addAttribute("jour", j);
        model.addAttribute("jourTexte", jourTexte);
        model.addAttribute("rappels", rappels);
        return "planning/rappels";
    }

    @PostMapping("/{rdv}")
    public String fait(@PathVariable UUID rdv, @RequestParam String canal, @RequestParam String jour,
                       @AuthenticationPrincipal MedsolUser user, RedirectAttributes ra) {
        if (CANAUX.contains(canal) && jdbc.update("""
                INSERT INTO rdv_rappel (cabinet_id, rdv_id, canal, par) SELECT current_cabinet(), r.id, ?, ? FROM rendez_vous r WHERE r.id = ?""",
                canal, user.id(), rdv) == 1) {
            audit.log("RAPPEL_" + canal, "rendez_vous", rdv);
            ra.addFlashAttribute("succes", textes.t("msg.reminder.done"));
        }
        return "redirect:/planning/rappels?jour=" + LocalDate.parse(jour);
    }

    /** « 06 12 34 56 78 » au Maroc → « 212612345678 » ; un numéro déjà international est gardé. */
    public static String international(String telephone, String pays) {
        if (telephone == null) return null;
        String t = telephone.strip();
        boolean plus = t.startsWith("+");
        String chiffres = t.replaceAll("\\D", "");
        if (chiffres.length() < 6) return null;
        if (plus) return chiffres;
        if (chiffres.startsWith("00")) return chiffres.substring(2);
        String indicatif = INDICATIFS.get(pays);
        if (indicatif == null) return null;
        if (chiffres.startsWith("0")) return indicatif + chiffres.substring(1);
        return chiffres.startsWith(indicatif) && chiffres.length() > 10 ? chiffres : indicatif + chiffres;
    }
}
