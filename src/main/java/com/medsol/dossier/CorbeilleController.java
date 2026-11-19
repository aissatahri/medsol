package com.medsol.dossier;

import com.medsol.audit.AuditService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * Corbeille des consultations (reprise de SGCM-Cardio) : supprimées avec leur contenu, absentes des dossiers, de la
 * caisse et des statistiques, rétablies à l'identique. Médecin uniquement (/consultations/**).
 */
@Controller
public class CorbeilleController {

    private final ConsultationRepository consultations;
    private final AuditService audit;

    private final com.medsol.web.Textes textes;

    public CorbeilleController(ConsultationRepository consultations, AuditService audit, com.medsol.web.Textes textes) {
        this.textes = textes;
        this.consultations = consultations;
        this.audit = audit;
    }

    @GetMapping("/consultations/corbeille")
    public String page(Model model) {
        model.addAttribute("elements", consultations.corbeille());
        return "dossier/corbeille";
    }

    @PostMapping("/consultations/corbeille/{id}/retablir")
    public String retablir(@PathVariable UUID id, RedirectAttributes flash) {
        if (consultations.retablir(id)) {
            audit.log("RETABLISSEMENT", "consultation", id);
            flash.addFlashAttribute("succes", textes.t("msg.bin.restored"));
        } else {
            flash.addFlashAttribute("erreur", textes.t("msg.bin.restoreFailed"));
        }
        return "redirect:/consultations/corbeille";
    }
}
