package com.medsol.patient;

import com.medsol.audit.AuditService;
import com.medsol.dossier.AllergiesRepository;
import com.medsol.dossier.ConsultationRepository;
import com.medsol.security.MedsolUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/** Patients : liste, fiche et formulaire repris de SGCM-Cardio Web (médecin et assistante). */
@Controller
@RequestMapping("/patients")
public class PatientController {

    private static final int PAGE_SIZE = 25;

    private final PatientRepository patients;
    private final AllergiesRepository allergies;
    private final ConsultationRepository consultations;
    private final AuditService audit;

    private final com.medsol.web.Textes textes;

    public PatientController(PatientRepository patients, AllergiesRepository allergies,
                             ConsultationRepository consultations, AuditService audit, com.medsol.web.Textes textes) {
        this.textes = textes;
        this.patients = patients;
        this.allergies = allergies;
        this.consultations = consultations;
        this.audit = audit;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page,
                       @RequestHeader(value = "HX-Request", required = false) String htmx, Model model) {
        PatientRepository.Page result = patients.search(q, Math.max(page, 0), PAGE_SIZE);
        model.addAttribute("q", q.strip());
        model.addAttribute("result", result);
        model.addAttribute("patients", result.items());
        model.addAttribute("page", result.page());
        model.addAttribute("suivante", result.hasNext());
        return htmx != null ? "patients/list :: resultats" : "patients/list";
    }

    /** Sélecteur de patient (boutons radio) pour les formulaires de salle d'attente et de rendez-vous. */
    @GetMapping("/lookup")
    public String lookup(@RequestParam(defaultValue = "") String q, Model model) {
        model.addAttribute("items", q.trim().length() < 2 ? List.of() : patients.search(q, 0, 8).items());
        return "patients/lookup :: options";
    }

    @GetMapping("/new")
    public String create(Model model) {
        return formulaire(new PatientForm(), null, List.of(), model);
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") PatientForm form, BindingResult result,
                         @AuthenticationPrincipal MedsolUser user, Model model, RedirectAttributes ra) {
        if (result.hasErrors()) return formulaire(form, null, List.of(), model);
        List<Patient> semblables = form.isIgnorerDoublon() ? List.of() : patients.semblables(form, null);
        if (!semblables.isEmpty()) return formulaire(form, null, semblables, model);
        UUID id = patients.create(form, user);
        audit.log("CREATION", "patient", id);
        ra.addFlashAttribute("succes", textes.t("msg.patient.created"));
        return "redirect:/patients/" + id;
    }

    @GetMapping("/{id}")
    public String show(@PathVariable UUID id, @AuthenticationPrincipal MedsolUser user, Model model) {
        Patient p = trouver(id);
        audit.log("LECTURE", "patient", id);
        boolean medecin = "MEDECIN".equals(user.role());
        model.addAttribute("patient", p);
        model.addAttribute("allergies", allergies.liste(id));
        model.addAttribute("antecedents", medecin ? allergies.antecedents(id) : List.of());
        model.addAttribute("typesAntecedent", AllergiesRepository.TYPES_ANTECEDENT);
        model.addAttribute("visites", consultations.visites(id));
        return "patients/show";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable UUID id, Model model) {
        return formulaire(PatientForm.from(trouver(id)), id, List.of(), model);
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") PatientForm form, BindingResult result,
                         @AuthenticationPrincipal MedsolUser user, Model model, RedirectAttributes ra) {
        trouver(id);
        if (result.hasErrors()) return formulaire(form, id, List.of(), model);
        List<Patient> semblables = form.isIgnorerDoublon() ? List.of() : patients.semblables(form, id);
        if (!semblables.isEmpty()) return formulaire(form, id, semblables, model);
        patients.update(id, form, "MEDECIN".equals(user.role()));
        audit.log("MODIFICATION", "patient", id);
        ra.addFlashAttribute("succes", textes.t("msg.patient.saved"));
        return "redirect:/patients/" + id;
    }

    @PostMapping("/{id}/archive")
    public String archive(@PathVariable UUID id, RedirectAttributes ra) {
        trouver(id);
        patients.archive(id);
        audit.log("ARCHIVAGE", "patient", id);
        ra.addFlashAttribute("succes", textes.t("msg.patient.archived"));
        return "redirect:/patients";
    }

    /** Dossiers archivés (médecin) : pour retrouver un dossier et le remettre dans les listes. */
    @GetMapping("/archives")
    public String archives(Model model) {
        model.addAttribute("archives", patients.archives());
        return "patients/archives";
    }

    @PostMapping("/{id}/restaurer")
    public String restaurer(@PathVariable UUID id, RedirectAttributes ra) {
        if (patients.restaurer(id)) {
            audit.log("RESTAURATION", "patient", id);
            ra.addFlashAttribute("succes", textes.t("msg.patient.restored"));
            return "redirect:/patients/" + id;
        }
        return "redirect:/patients/archives";
    }

    private String formulaire(PatientForm form, UUID id, List<Patient> semblables, Model model) {
        model.addAttribute("form", form);
        model.addAttribute("patientId", id);
        model.addAttribute("semblables", semblables);
        model.addAttribute("duplicate", !semblables.isEmpty());
        model.addAttribute("couvertures", patients.couvertures());
        return "patients/form";
    }

    private Patient trouver(UUID id) {
        return patients.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
