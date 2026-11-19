package com.medsol.caisse;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.dossier.ConsultationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

/** Actes réalisés pendant la consultation (payé ou offert) ; le total de la caisse suit. Médecin (/consultations/**). */
@Controller
@RequestMapping("/consultations/{id}/actes")
public class ActesController {

    private final CaisseRepository caisse;
    private final ConsultationRepository consultations;
    private final CabinetService cabinets;
    private final AuditService audit;

    private final com.medsol.web.Textes textes;

    public ActesController(CaisseRepository caisse, ConsultationRepository consultations, CabinetService cabinets, AuditService audit, com.medsol.web.Textes textes) {
        this.textes = textes;
        this.caisse = caisse;
        this.consultations = consultations;
        this.cabinets = cabinets;
        this.audit = audit;
    }

    @GetMapping
    public String actes(@PathVariable UUID id, Model model) {
        trouver(id);
        return remplir(id, null, model);
    }

    /** Champs a_{acte} = « paye » ou « offert » ; un acte non choisi est retiré. */
    @PostMapping
    public String enregistrer(@PathVariable UUID id, @RequestParam Map<String, String> formulaire, Model model) {
        trouver(id);
        Map<UUID, Boolean> choix = new LinkedHashMap<>();
        formulaire.forEach((nom, valeur) -> {
            if (nom.startsWith("a_") && (valeur.equals("paye") || valeur.equals("offert"))) {
                try {
                    choix.put(UUID.fromString(nom.substring(2)), valeur.equals("paye"));
                } catch (IllegalArgumentException ignore) {
                    // champ inconnu
                }
            }
        });
        caisse.enregistrerActes(id, choix);
        audit.log("ACTES_MODIFICATION", "consultation", id);
        return remplir(id, textes.t("msg.acts.saved"), model);
    }

    private String remplir(UUID id, String message, Model model) {
        Map<UUID, Boolean> etat = new LinkedHashMap<>();
        caisse.actes(id).forEach(a -> etat.put(a.acteId(), a.paye()));
        BigDecimal total = caisse.total(id);
        BigDecimal verse = caisse.verse(id);
        model.addAttribute("consultationId", id);
        model.addAttribute("catalogue", caisse.catalogue(false));
        model.addAttribute("etat", etat);
        model.addAttribute("total", total);
        model.addAttribute("verse", verse);
        model.addAttribute("devise", cabinets.current().devise());
        model.addAttribute("messageActes", message);
        return "caisse/actes :: actes";
    }

    private void trouver(UUID id) {
        consultations.trouver(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
