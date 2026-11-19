package com.medsol.settings;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.caisse.CaisseRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.UUID;

/** Catalogue des actes et de leurs tarifs (Paramètres › Actes du bureau). Médecin. */
@Controller
@RequestMapping("/settings/actes")
public class ActesSettingsController {

    private final CaisseRepository caisse;
    private final CabinetService cabinets;
    private final AuditService audit;

    public ActesSettingsController(CaisseRepository caisse, CabinetService cabinets, AuditService audit) {
        this.caisse = caisse;
        this.cabinets = cabinets;
        this.audit = audit;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("actes", caisse.catalogue(true));
        model.addAttribute("devise", cabinets.current().devise());
        return "settings/actes";
    }

    @PostMapping
    public String ajouter(@RequestParam String nom, @RequestParam String prix, RedirectAttributes ra) {
        BigDecimal p = montant(prix);
        if (nom.isBlank() || nom.length() > 150 || p == null) {
            ra.addFlashAttribute("error", "actes.invalid");
        } else if (!caisse.ajouterActe(nom, p)) {
            ra.addFlashAttribute("error", "actes.exists");
        } else {
            audit.log("ACTE_CREATION", "acte", null);
            ra.addFlashAttribute("done", "settings.saved");
        }
        return "redirect:/settings/actes";
    }

    /** Nouveau tarif (les consultations passées gardent leur prix) ; un acte retiré n'est plus proposé. */
    @PostMapping("/{id}")
    public String modifier(@PathVariable UUID id, @RequestParam String prix, @RequestParam(defaultValue = "false") boolean actif,
                           RedirectAttributes ra) {
        BigDecimal p = montant(prix);
        if (p == null) {
            ra.addFlashAttribute("error", "actes.invalid");
        } else {
            caisse.modifierActe(id, p, actif);
            audit.log("ACTE_MODIFICATION", "acte", id);
            ra.addFlashAttribute("done", "settings.saved");
        }
        return "redirect:/settings/actes";
    }

    private static BigDecimal montant(String s) {
        try {
            BigDecimal b = new BigDecimal(s.strip().replace(',', '.').replace(" ", ""));
            return b.signum() < 0 || b.compareTo(new BigDecimal("10000000")) > 0 ? null : b;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
