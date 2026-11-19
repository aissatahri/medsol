package com.medsol.web;

import com.medsol.patient.PatientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Recherche de patient de la barre du haut, sur toutes les pages (repris de SGCM-Cardio Web). */
@Controller
public class SearchController {

    private final PatientRepository patients;

    public SearchController(PatientRepository patients) { this.patients = patients; }

    @GetMapping("/recherche")
    public String recherche(@RequestParam(defaultValue = "") String q, Model model) {
        String t = q.strip();
        var page = t.length() < 2 ? null : patients.search(t, 0, 8);
        model.addAttribute("q", t);
        model.addAttribute("trouves", page == null ? java.util.List.of() : page.items());
        model.addAttribute("autres", page != null && page.total() > page.items().size());
        return "patients/recherche-globale :: resultats";
    }
}
