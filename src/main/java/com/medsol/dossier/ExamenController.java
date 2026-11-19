package com.medsol.dossier;

import com.medsol.audit.AuditService;
import com.medsol.dossier.ExamenRepository.Genre;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Analyses et imagerie d'une consultation (repris d'ExamenController de SGCM-Cardio). Médecin uniquement. */
@Controller
@RequestMapping("/consultations/{c}/examens/{genre:analyse|imagerie}")
public class ExamenController {

    private final ExamenRepository examens;
    private final ConsultationRepository consultations;
    private final AuditService audit;

    private final com.medsol.web.Textes textes;

    public ExamenController(ExamenRepository examens, ConsultationRepository consultations, AuditService audit, com.medsol.web.Textes textes) {
        this.textes = textes;
        this.examens = examens;
        this.consultations = consultations;
        this.audit = audit;
    }

    @GetMapping
    public String carte(@PathVariable UUID c, @PathVariable String genre, Model model) {
        return carte(c, Genre.depuis(genre), null, null, model);
    }

    @GetMapping("/types")
    public String types(@PathVariable UUID c, @PathVariable String genre, @RequestParam(defaultValue = "") String q, Model model) {
        Genre g = Genre.depuis(genre);
        List<ExamenRepository.Type> types = examens.types(g, q, 12);
        model.addAttribute("g", g);
        model.addAttribute("consultationId", c);
        model.addAttribute("types", types);
        model.addAttribute("qType", q.strip());
        model.addAttribute("proposerAjout", q.strip().length() >= 2
                && types.stream().noneMatch(t -> t.nom() != null && t.nom().strip().equalsIgnoreCase(q.strip())));
        return "dossier/examens :: choix";
    }

    @PostMapping
    public String ajouter(@PathVariable UUID c, @PathVariable String genre, @RequestParam long typeId,
                          @RequestParam(defaultValue = "") String description, Model model) {
        Genre g = Genre.depuis(genre);
        trouver(c);
        boolean ok = examens.ajouter(g, c, typeId, borne(description, 300));
        if (ok) audit.log(g.name() + "_DEMANDE", "consultation", c);
        return carte(c, g, ok ? null : textes.t("msg.consultation.gone"), null, model);
    }

    @GetMapping("/catalogue")
    public String nouveauType(@PathVariable UUID c, @PathVariable String genre, @RequestParam(defaultValue = "") String nom, Model model) {
        model.addAttribute("g", Genre.depuis(genre));
        model.addAttribute("consultationId", c);
        model.addAttribute("nomPropose", nom.strip());
        return "dossier/examens :: nouveauType";
    }

    @PostMapping("/catalogue")
    public String ajouterAuCatalogue(@PathVariable UUID c, @PathVariable String genre, @RequestParam String nom,
                                     @RequestParam(defaultValue = "") String code, @RequestParam(defaultValue = "") String description,
                                     Model model) {
        Genre g = Genre.depuis(genre);
        trouver(c);
        String n = nom.strip();
        if (n.length() < 2 || n.length() > 200 || code.length() > 50) {
            model.addAttribute("g", g);
            model.addAttribute("consultationId", c);
            model.addAttribute("nomPropose", n);
            model.addAttribute("erreurCatalogue", textes.t("msg.exam.nameRequired"));
            return "dossier/examens :: nouveauType";
        }
        long type = examens.trouverOuCreerType(g, n, code);
        examens.ajouter(g, c, type, borne(description, 300));
        audit.log(g.name() + "_DEMANDE", "consultation", c);
        return carte(c, g, null, "« " + n + " » est demandé ; il est désormais dans le catalogue du cabinet.", model);
    }

    @GetMapping("/{e}/modifier")
    public String edition(@PathVariable UUID c, @PathVariable String genre, @PathVariable UUID e, Model model) {
        Genre g = Genre.depuis(genre);
        model.addAttribute("g", g);
        model.addAttribute("consultationId", c);
        model.addAttribute("e", examens.trouver(g, c, e).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "dossier/examens :: edition";
    }

    @PostMapping("/{e}")
    public String enregistrer(@PathVariable UUID c, @PathVariable String genre, @PathVariable UUID e,
                              @RequestParam(defaultValue = "") String description, @RequestParam(defaultValue = "") String resultat,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Model model) {
        Genre g = Genre.depuis(genre);
        boolean ok = examens.modifier(g, c, e, borne(description, 300), borne(resultat, 10000), date);
        if (ok) audit.log(g.name() + "_RESULTAT", "consultation", c);
        return carte(c, g, ok ? null : textes.t("msg.rx.lineGone"), ok ? textes.t("msg.exam.saved") : null, model);
    }

    @PostMapping("/{e}/supprimer")
    public String supprimer(@PathVariable UUID c, @PathVariable String genre, @PathVariable UUID e, Model model) {
        Genre g = Genre.depuis(genre);
        if (examens.supprimer(g, c, e)) audit.log(g.name() + "_SUPPRESSION", "consultation", c);
        return carte(c, g, null, null, model);
    }

    private String carte(UUID c, Genre g, String erreur, String succes, Model model) {
        model.addAttribute("g", g);
        model.addAttribute("consultationId", c);
        model.addAttribute("examens", examens.liste(g, c));
        model.addAttribute("erreur", erreur);
        model.addAttribute("succes", succes);
        return "dossier/examens :: examens";
    }

    private void trouver(UUID c) {
        consultations.trouver(c).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String borne(String s, int max) { return s == null ? "" : s.length() > max ? s.substring(0, max) : s; }
}
