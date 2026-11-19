package com.medsol.settings;

import com.medsol.audit.AuditService;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Paramètres › Consultation : champs cliniques et constantes de la consultation. Les modèles de MedSol (communs et de
 * la spécialité) se masquent pour ce cabinet ; le médecin ajoute ses propres champs et constantes. Médecin.
 */
@Controller
@RequestMapping("/settings/consultation")
public class ConsultationSettingsController {

    public record Type(long id, String label, String unite, boolean propre, boolean masque, boolean complexe) {}

    private static final Set<String> GENRES = Set.of("CHAMP", "CONSTANTE");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ConsultationSettingsController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping
    public String page(Model model) {
        String l = LocaleContextHolder.getLocale().getLanguage();
        model.addAttribute("champs", jdbc.query("""
                SELECT t.id, coalesce(t.libelle->>?, t.libelle->>'fr'), NULL, t.cabinet_id IS NOT NULL,
                       EXISTS (SELECT 1 FROM type_masque m WHERE m.genre = 'CHAMP' AND m.type_id = t.id), false
                FROM type_champ_clinique t WHERE t.actif AND (t.specialite IS NULL OR t.specialite = (SELECT specialite FROM cabinet))
                ORDER BY t.ordre, t.id""", (rs, i) -> type(rs), l));
        model.addAttribute("constantes", jdbc.query("""
                SELECT t.id, coalesce(t.libelle->>?, t.libelle->>'fr'), t.unite, t.cabinet_id IS NOT NULL,
                       EXISTS (SELECT 1 FROM type_masque m WHERE m.genre = 'CONSTANTE' AND m.type_id = t.id), t.complexe
                FROM type_constante t WHERE t.actif AND (t.specialite IS NULL OR t.specialite = (SELECT specialite FROM cabinet))
                ORDER BY t.ordre, t.id""", (rs, i) -> type(rs), l));
        return "settings/consultation";
    }

    /** Modèle de MedSol : masqué / affiché pour ce cabinet. */
    @PostMapping("/{genre}/{id}/masque")
    public String masquer(@PathVariable String genre, @PathVariable long id, @RequestParam boolean masque, RedirectAttributes ra) {
        if (!GENRES.contains(genre)) return "redirect:/settings/consultation";
        if (masque) {
            jdbc.update("INSERT INTO type_masque (cabinet_id, genre, type_id) VALUES (current_cabinet(), ?, ?) ON CONFLICT DO NOTHING", genre, id);
        } else {
            jdbc.update("DELETE FROM type_masque WHERE genre = ? AND type_id = ?", genre, id);
        }
        audit.log("PARAMETRAGE_CONSULTATION", genre.toLowerCase(), id);
        ra.addFlashAttribute("done", "settings.saved");
        return "redirect:/settings/consultation";
    }

    /** Champ clinique ou constante propre au cabinet. */
    @PostMapping("/{genre}")
    public String ajouter(@PathVariable String genre, @RequestParam String libelle, @RequestParam(defaultValue = "") String unite,
                          RedirectAttributes ra) {
        String lib = libelle.strip();
        if (!GENRES.contains(genre) || lib.isEmpty() || lib.length() > 100 || unite.length() > 20) {
            ra.addFlashAttribute("error", "consultation.settings.invalid");
            return "redirect:/settings/consultation";
        }
        String nom = "CAB_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String json = "{\"fr\":" + jsonTexte(lib) + ",\"en\":" + jsonTexte(lib) + "}";
        if ("CHAMP".equals(genre)) {
            jdbc.update("INSERT INTO type_champ_clinique (cabinet_id, nom, libelle, ordre) VALUES (current_cabinet(), ?, ?::jsonb, 50)", nom, json);
        } else {
            jdbc.update("INSERT INTO type_constante (cabinet_id, nom, libelle, unite, ordre) VALUES (current_cabinet(), ?, ?::jsonb, ?, 500)",
                    nom, json, unite.isBlank() ? null : unite.strip());
        }
        audit.log("PARAMETRAGE_CONSULTATION_AJOUT", genre.toLowerCase(), lib);
        ra.addFlashAttribute("done", "settings.saved");
        return "redirect:/settings/consultation";
    }

    /** Retire un champ propre au cabinet (les valeurs déjà saisies restent dans les consultations passées). */
    @PostMapping("/{genre}/{id}/retirer")
    public String retirer(@PathVariable String genre, @PathVariable long id, RedirectAttributes ra) {
        String table = "CHAMP".equals(genre) ? "type_champ_clinique" : "CONSTANTE".equals(genre) ? "type_constante" : null;
        if (table != null) jdbc.update("UPDATE " + table + " SET actif = false WHERE id = ? AND cabinet_id = current_cabinet()", id);
        ra.addFlashAttribute("done", "settings.saved");
        return "redirect:/settings/consultation";
    }

    private static Type type(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Type(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6));
    }

    private static String jsonTexte(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
