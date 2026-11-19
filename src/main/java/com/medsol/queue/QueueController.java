package com.medsol.queue;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.patient.PatientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Salle d'attente reprise de SGCM-Cardio Web : ajout avec état, filtres, statuts, retrait ; relue toutes les 10 s. */
@Controller
@RequestMapping("/queue")
public class QueueController {

    public record Message(String texte, boolean erreur) {}

    private static final List<String> PAR_DEFAUT = List.of("EN_ATTENTE", "EN_CONSULTATION");

    private final QueueRepository queue;
    private final PatientRepository patients;
    private final CabinetService cabinets;
    private final AuditService audit;

    private final com.medsol.web.Textes textes;

    public QueueController(QueueRepository queue, PatientRepository patients, CabinetService cabinets, AuditService audit, com.medsol.web.Textes textes) {
        this.textes = textes;
        this.queue = queue;
        this.patients = patients;
        this.cabinets = cabinets;
        this.audit = audit;
    }

    @GetMapping
    public String page(Model model) {
        LocalDate jour = jour();
        model.addAttribute("statuts", PAR_DEFAUT);
        model.addAttribute("lignes", queue.today(jour, PAR_DEFAUT));
        model.addAttribute("nbAttente", queue.today(jour, List.of("EN_ATTENTE")).size());
        return "queue/index";
    }

    @GetMapping("/list")
    public String list(@RequestParam(required = false) List<String> statuts, Model model) {
        return file(statuts, null, model, "queue/index :: file");
    }

    @GetMapping("/recherche")
    public String recherche(@RequestParam(defaultValue = "") String q, Model model) {
        String t = q.strip();
        model.addAttribute("q", t);
        model.addAttribute("trouves", t.length() < 2 ? List.of() : patients.search(t, 0, 8).items());
        model.addAttribute("presents", queue.presents(jour()));
        return "queue/index :: trouves";
    }

    /** Ajout depuis la salle d'attente (htmx) : liste à jour et message. */
    @PostMapping
    public String ajouter(@RequestParam UUID patientId, @RequestParam(defaultValue = "NORMAL") String etat,
                          @RequestParam(required = false) List<String> statuts, Model model) {
        String nom = patients.find(patientId).map(p -> p.nomComplet()).orElse(null);
        Message m;
        if (nom == null) {
            m = new Message(textes.t("msg.queue.notFound"), true);
        } else if (queue.add(patientId, null, null, jour(), etat)) {
            audit.log("SALLE_ATTENTE_AJOUT", "patient", patientId);
            m = new Message(textes.t("msg.queue.added", nom), false);
        } else {
            m = new Message(textes.t("msg.queue.already", nom), true);
        }
        return file(statuts, m, model, "queue/reponse");
    }

    /** Ajout depuis la fiche ou la liste des patients (sans htmx). */
    @PostMapping("/add")
    public String add(@RequestParam UUID patientId, @RequestParam(required = false) String motif, RedirectAttributes ra) {
        if (queue.add(patientId, motif, null, jour())) audit.log("SALLE_ATTENTE_AJOUT", "patient", patientId);
        else ra.addFlashAttribute("error", "queue.already");
        return "redirect:/queue";
    }

    @PostMapping("/{id}/statut")
    public String statut(@PathVariable UUID id, @RequestParam String attendu, @RequestParam String nouveau,
                         @RequestParam(required = false) List<String> statuts, Model model) {
        Message m = switch (queue.statut(id, attendu, nouveau)) {
            case FAIT -> {
                audit.log("SALLE_ATTENTE_" + nouveau, "file_attente", id);
                yield null;
            }
            case OCCUPE -> new Message(textes.t("msg.queue.busy"), true);
            case PERIME -> new Message(textes.t("msg.queue.stale"), true);
        };
        return file(statuts, m, model, "queue/reponse");
    }

    @PostMapping("/{id}/retirer")
    public String retirer(@PathVariable UUID id, @RequestParam(required = false) List<String> statuts, Model model) {
        if (queue.remove(id)) audit.log("SALLE_ATTENTE_RETRAIT", "file_attente", id);
        return file(statuts, null, model, "queue/reponse");
    }

    private String file(List<String> statuts, Message message, Model model, String vue) {
        List<String> s = statuts == null || statuts.isEmpty() ? PAR_DEFAUT : statuts;
        model.addAttribute("statuts", s);
        model.addAttribute("lignes", queue.today(jour(), s));
        model.addAttribute("message", message);
        return vue;
    }

    private LocalDate jour() { return cabinets.current().today(); }
}
