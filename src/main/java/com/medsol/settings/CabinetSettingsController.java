package com.medsol.settings;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Coordonnées imprimées sur les documents (en-tête des ordonnances). Réservé au médecin. */
@Controller
@RequestMapping("/settings/cabinet")
public class CabinetSettingsController {

    private final CabinetService cabinets;
    private final AuditService audit;
    private final com.medsol.patient.PatientRepository patients;

    public CabinetSettingsController(CabinetService cabinets, AuditService audit, com.medsol.patient.PatientRepository patients) {
        this.cabinets = cabinets;
        this.audit = audit;
        this.patients = patients;
    }

    @GetMapping
    public String form(Model model) {
        CabinetService.Cabinet c = cabinets.current();
        model.addAttribute("cabinet", c);
        model.addAttribute("villeProposee", c.villeDocuments());
        model.addAttribute("couverturesCommunes", patients.couverturesCommunes());
        model.addAttribute("couverturesCabinet", patients.couverturesDuCabinet());
        return "settings/cabinet";
    }

    /** Couverture sanitaire propre au cabinet (mutuelle, assureur…), proposée ensuite dans la fiche patient. */
    @PostMapping("/couvertures")
    public String ajouterCouverture(@RequestParam String libelle, RedirectAttributes ra) {
        String l = libelle == null ? "" : libelle.strip();
        if (l.length() < 2 || l.length() > 100) {
            ra.addFlashAttribute("error", "users.invalid");
        } else if (!patients.ajouterCouverture(l)) {
            ra.addFlashAttribute("error", "cov.exists");
        } else {
            audit.log("COUVERTURE_AJOUTEE", "cabinet", null);
            ra.addFlashAttribute("done", "cov.added");
        }
        return "redirect:/settings/cabinet#couvertures";
    }

    @PostMapping("/couvertures/{id}/supprimer")
    public String supprimerCouverture(@PathVariable long id, RedirectAttributes ra) {
        patients.supprimerCouverture(id);
        ra.addFlashAttribute("done", "cov.deleted");
        return "redirect:/settings/cabinet#couvertures";
    }

    @PostMapping
    public String save(@RequestParam String nom, @RequestParam(required = false) String adresse,
                       @RequestParam(required = false) String telephone,
                       @RequestParam(required = false) String numeroOrdre, @RequestParam(required = false) String mentions,
                       @RequestParam(required = false) String identifiant, @RequestParam(required = false) String ville,
                       @RequestParam(required = false) String identifiantFiscal, @RequestParam(required = false) String ice,
                       @RequestParam(required = false) String taxeProfessionnelle,
                       @RequestParam(defaultValue = "false") boolean papierEntete, RedirectAttributes ra) {
        String iceNet = ice == null ? null : ice.replaceAll("\\s", "");
        if (nom.isBlank() || nom.length() > 150 || len(adresse) > 300 || len(telephone) > 40 || len(numeroOrdre) > 60
                || len(mentions) > 1000 || len(identifiant) > 60 || len(ville) > 80 || len(identifiantFiscal) > 30
                || len(taxeProfessionnelle) > 30) {
            ra.addFlashAttribute("error", "users.invalid");
        } else if (iceNet != null && !iceNet.isEmpty() && !iceNet.matches("[0-9]{15}")) {
            ra.addFlashAttribute("error", "settings.ice.invalid");
        } else {
            cabinets.updateContact(nom, adresse, telephone, numeroOrdre);
            cabinets.updateFacturation(mentions, identifiant);
            cabinets.updateDocuments(ville, identifiantFiscal, iceNet, taxeProfessionnelle, papierEntete);
            audit.log("CABINET_MODIFICATION", "cabinet", null);
            ra.addFlashAttribute("done", "settings.saved");
        }
        return "redirect:/settings/cabinet";
    }

    private static int len(String s) { return s == null ? 0 : s.length(); }
}
