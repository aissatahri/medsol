package com.medsol.settings;

import com.medsol.appointment.SmsService;
import com.medsol.audit.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/** Paramètres › Rappels : rappels automatiques des rendez-vous par e-mail et par SMS (médecin). */
@Controller
@RequestMapping("/settings/rappels")
public class RappelsSettingsController {

    private final JdbcTemplate jdbc;
    private final SmsService sms;
    private final AuditService audit;

    public RappelsSettingsController(JdbcTemplate jdbc, SmsService sms, AuditService audit) {
        this.jdbc = jdbc;
        this.sms = sms;
        this.audit = audit;
    }

    @GetMapping
    public String page(Model model) {
        Map<String, Object> r = jdbc.queryForMap("SELECT rappel_auto_email AS email, rappel_auto_sms AS sms, rappel_heures AS heures FROM cabinet");
        model.addAttribute("r", r);
        model.addAttribute("smsDisponible", sms.actif());
        model.addAttribute("sansEmail", jdbc.queryForObject("""
                SELECT count(*) FROM patient WHERE coalesce(email, '') NOT LIKE '%@%'""", Long.class));
        model.addAttribute("patients", jdbc.queryForObject("SELECT count(*) FROM patient", Long.class));
        return "settings/rappels";
    }

    @PostMapping
    public String enregistrer(@RequestParam(defaultValue = "false") boolean email, @RequestParam(defaultValue = "false") boolean sms,
                              @RequestParam(defaultValue = "24") int heures, RedirectAttributes ra) {
        if (heures < 2 || heures > 72) {
            ra.addFlashAttribute("error", "users.invalid");
        } else {
            jdbc.update("UPDATE cabinet SET rappel_auto_email = ?, rappel_auto_sms = ?, rappel_heures = ? WHERE id = current_cabinet()",
                    email, sms && this.sms.actif(), heures);
            audit.log("RAPPELS_AUTOMATIQUES", "cabinet", null);
            ra.addFlashAttribute("done", "settings.saved");
        }
        return "redirect:/settings/rappels";
    }
}
