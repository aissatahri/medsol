package com.medsol.dossier;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.queue.QueueRepository;
import com.medsol.security.MedsolUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

/**
 * Consultation (champs cliniques, constantes) et ordonnance, repris de SGCM-Cardio Web : chaque carte se met à jour
 * seule (htmx), sans recharger le dossier. Médecin uniquement.
 */
@Controller
public class ConsultationController {

    public record Message(String texte, boolean erreur) {}

    private final ConsultationRepository consultations;
    private final OrdonnanceRepository ordonnances;
    private final OrdonnanceTypeRepository modeles;
    private final AllergiesRepository allergies;
    private final Catalogues catalogues;
    private final QueueRepository queue;
    private final CabinetService cabinets;
    private final AuditService audit;
    private final AlertesPrescription alertes;
    private final com.medsol.web.Textes textes;

    public ConsultationController(ConsultationRepository consultations, OrdonnanceRepository ordonnances,
                                  OrdonnanceTypeRepository modeles, AllergiesRepository allergies, Catalogues catalogues,
                                  QueueRepository queue, CabinetService cabinets, AuditService audit, AlertesPrescription alertes,
                                  com.medsol.web.Textes textes) {
        this.alertes = alertes;
        this.textes = textes;
        this.consultations = consultations;
        this.ordonnances = ordonnances;
        this.modeles = modeles;
        this.allergies = allergies;
        this.catalogues = catalogues;
        this.queue = queue;
        this.cabinets = cabinets;
        this.audit = audit;
    }

    /** Depuis l'accueil du médecin : appelle le patient de la salle d'attente et ouvre sa consultation du jour. */
    @PostMapping("/consultations/from-queue/{entryId}")
    public String fromQueue(@PathVariable UUID entryId, @AuthenticationPrincipal MedsolUser user, RedirectAttributes ra) {
        var entry = queue.find(entryId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("EN_ATTENTE".equals(entry.statut())) {
            if (!queue.call(entryId)) {
                ra.addFlashAttribute("error", "queue.busy");
                return "redirect:/";
            }
            audit.log("SALLE_ATTENTE_APPEL", "file_attente", entryId);
        } else if (!"EN_CONSULTATION".equals(entry.statut())) {
            return "redirect:/";
        }
        UUID cid = consultations.duJour(entry.patientId(), user.id(), cabinets.current().today());
        return "redirect:" + DossierController.adresse(entry.patientId(), "consultation", cid);
    }

    /** Ouvre la consultation du jour du patient (créée au besoin). */
    @PostMapping("/consultations/start")
    public String start(@RequestParam UUID patientId, @AuthenticationPrincipal MedsolUser user) {
        UUID cid = consultations.duJour(patientId, user.id(), cabinets.current().today());
        return "redirect:" + DossierController.adresse(patientId, "consultation", cid);
    }

    /** Enregistre champs cliniques et constantes ; réaffiche le formulaire avec ce qui est en base. */
    @PostMapping("/consultations/{id}")
    public String enregistrer(@PathVariable UUID id, @RequestParam Map<String, String> formulaire, Model model) {
        ConsultationRepository.Consultation c = trouver(id);
        List<Catalogues.TypeChamp> champs = catalogues.champs();
        List<Catalogues.TypeConstante> constantes = catalogues.constantes();
        Message message;
        ConsultationRepository.Saisie saisie;
        try {
            saisie = consultations.enregistrer(id, champs, constantes, saisie(formulaire));
            audit.log("MODIFICATION", "consultation", id);
            message = new Message(textes.t("msg.consultation.saved"), false);
        } catch (RuntimeException e) {
            saisie = consultations.lire(id);
            message = new Message(textes.t("msg.consultation.notSaved"), true);
        }
        model.addAttribute("consultation", c);
        model.addAttribute("saisie", saisie);
        model.addAttribute("typesChamps", champs);
        model.addAttribute("typesConstantes", constantes);
        model.addAttribute("message", message);
        return "dossier/consultation :: formulaire";
    }

    // ── Ordonnance ──────────────────────────────────────────────────────

    @GetMapping("/consultations/{id}/ordonnance")
    public String ordonnance(@PathVariable UUID id, Model model) {
        trouver(id);
        return ordonnanceAJour(id, null, model);
    }

    @GetMapping("/consultations/{id}/medicaments")
    public String medicaments(@PathVariable UUID id, @RequestParam(defaultValue = "") String q, Model model) {
        trouver(id);
        List<OrdonnanceRepository.Medicament> trouves = ordonnances.rechercher(q, 12);
        model.addAttribute("consultation", reference(id));
        model.addAttribute("medicaments", trouves);
        model.addAttribute("qMedicament", q.strip());
        model.addAttribute("proposerAjout", q.strip().length() >= 2
                && trouves.stream().noneMatch(m -> m.nom() != null && m.nom().strip().equalsIgnoreCase(q.strip())));
        return "dossier/consultation :: choix";
    }

    @GetMapping("/consultations/{id}/ordonnance/catalogue")
    public String nouveauMedicament(@PathVariable UUID id, @RequestParam(defaultValue = "") String nom, Model model) {
        model.addAttribute("consultation", reference(id));
        model.addAttribute("nomPropose", nom.strip());
        return "dossier/consultation :: nouveauMedicament";
    }

    @PostMapping("/consultations/{id}/ordonnance/catalogue")
    public String ajouterAuCatalogue(@PathVariable UUID id, @RequestParam String nom, @RequestParam(defaultValue = "") String forme,
                                     @RequestParam(defaultValue = "") String dci, @RequestParam(defaultValue = "") String dose,
                                     @RequestParam(defaultValue = "") String duree, @RequestParam(defaultValue = "") String remarque,
                                     Model model) {
        trouver(id);
        String n = nom.strip();
        if (n.length() < 2 || n.length() > 200) {
            model.addAttribute("consultation", reference(id));
            model.addAttribute("nomPropose", n);
            model.addAttribute("erreurCatalogue", textes.t("msg.rx.nameRequired"));
            return "dossier/consultation :: nouveauMedicament";
        }
        long medicamentId = ordonnances.trouverOuCreer(n, forme, dci);
        boolean ok = ordonnances.ajouter(id, medicamentId, dose, duree, remarque);
        if (ok) audit.log("ORDONNANCE_LIGNE", "consultation", id);
        return ordonnanceAJour(id, ok ? new Message(textes.t("msg.rx.addedToCatalogue", n), false)
                : new Message(textes.t("msg.consultation.gone"), true), model);
    }

    @GetMapping("/consultations/{id}/ordonnance/nouvelle")
    public String nouvelleLigne(@PathVariable UUID id, @RequestParam long medicamentId, Model model) {
        ConsultationRepository.Consultation c = trouver(id);
        OrdonnanceRepository.Medicament m = ordonnances.medicament(medicamentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("consultation", c);
        model.addAttribute("medicament", m);
        // ce que l'ajout de ce médicament déclencherait : allergie, doublon, interaction, âge
        model.addAttribute("alertesAjout", alertes.avantAjout(id, m.nom(), m.forme(), m.dci()));
        return "dossier/consultation :: nouvelleLigne";
    }

    @PostMapping("/consultations/{id}/ordonnance")
    public String ajouterLigne(@PathVariable UUID id, @RequestParam long medicamentId, @RequestParam(defaultValue = "") String dose,
                               @RequestParam(defaultValue = "") String duree, @RequestParam(defaultValue = "") String remarque,
                               Model model) {
        trouver(id);
        // prescrit malgré une contre-indication signalée : inscrit au journal (décision du médecin, tracée)
        boolean contreIndique = ordonnances.medicament(medicamentId)
                .map(m -> alertes.avantAjout(id, m.nom(), m.forme(), m.dci()).stream()
                        .anyMatch(a -> a.niveau() == ReglesPrescription.Niveau.CONTRE_INDICATION))
                .orElse(false);
        boolean ok = ordonnances.ajouter(id, medicamentId, borne(dose), borne(duree), borne(remarque));
        if (ok) audit.log("ORDONNANCE_LIGNE", "consultation", id);
        if (ok && contreIndique) audit.log("ALERTE_CONTRE_INDICATION_OUTREPASSEE", "consultation", id);
        return ordonnanceAJour(id, ok ? null : new Message(textes.t("msg.consultation.gone"), true), model);
    }

    @GetMapping("/consultations/{id}/ordonnance/{ligne}/modifier")
    public String modifierLigne(@PathVariable UUID id, @PathVariable UUID ligne, Model model) {
        model.addAttribute("consultation", reference(id));
        model.addAttribute("l", ordonnances.ligne(id, ligne).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "dossier/consultation :: ligneEdition";
    }

    @PostMapping("/consultations/{id}/ordonnance/{ligne}")
    public String enregistrerLigne(@PathVariable UUID id, @PathVariable UUID ligne, @RequestParam(defaultValue = "") String dose,
                                   @RequestParam(defaultValue = "") String duree, @RequestParam(defaultValue = "") String remarque,
                                   Model model) {
        boolean ok = ordonnances.modifier(id, ligne, borne(dose), borne(duree), borne(remarque));
        if (ok) audit.log("ORDONNANCE_MODIFICATION", "consultation", id);
        return ordonnanceAJour(id, ok ? null : new Message(textes.t("msg.rx.lineGone"), true), model);
    }

    @PostMapping("/consultations/{id}/ordonnance/{ligne}/supprimer")
    public String supprimerLigne(@PathVariable UUID id, @PathVariable UUID ligne, Model model) {
        if (ordonnances.supprimer(id, ligne)) audit.log("ORDONNANCE_SUPPRESSION", "consultation", id);
        return ordonnanceAJour(id, null, model);
    }

    @PostMapping("/consultations/{id}/ordonnance/reprendre")
    public String reprendre(@PathVariable UUID id, Model model) {
        trouver(id);
        Message message = ordonnances.precedenteAvecOrdonnance(id)
                .map(depuis -> ordonnances.reprendre(depuis, id))
                .map(n -> n > 0 ? new Message(textes.t("msg.rx.reused", n), false)
                        : new Message(textes.t("msg.rx.notEmpty"), true))
                .orElse(new Message(textes.t("msg.rx.noPrevious"), true));
        return ordonnanceAJour(id, message, model);
    }

    // ── Ordonnances types ───────────────────────────────────────────────

    @GetMapping("/consultations/{id}/ordonnance/modeles")
    public String modeles(@PathVariable UUID id, Model model) {
        return panneauModeles(id, null, model);
    }

    @PostMapping("/consultations/{id}/ordonnance/modeles")
    public String enregistrerModele(@PathVariable UUID id, @RequestParam(defaultValue = "") String nom, Model model) {
        trouver(id);
        if (nom.isBlank()) {
            return ordonnanceAJour(id, new Message(textes.t("msg.tpl.nameRequired"), true), model);
        }
        Message message = switch (modeles.enregistrer(nom, id)) {
            case ENREGISTRE -> new Message(textes.t("msg.tpl.saved", nom.strip()), false);
            case NOM_PRIS -> new Message(textes.t("msg.tpl.nameTaken", nom.strip()), true);
            case ORDONNANCE_VIDE -> new Message(textes.t("msg.tpl.empty"), true);
        };
        return ordonnanceAJour(id, message, model);
    }

    @PostMapping("/consultations/{id}/ordonnance/modeles/{modele}/appliquer")
    public String appliquerModele(@PathVariable UUID id, @PathVariable UUID modele,
                                  @RequestParam(required = false) List<UUID> lignes, Model model) {
        ConsultationRepository.Consultation c = trouver(id);
        Optional<OrdonnanceTypeRepository.Modele> contenu = modeles.modele(modele);
        OrdonnanceTypeRepository.Application a = modeles.appliquer(modele, id, lignes);
        if (!a.trouve()) return ordonnanceAJour(id, new Message(textes.t("msg.tpl.gone"), true), model);
        if (lignes == null || lignes.isEmpty()) {
            return ordonnanceAJour(id, new Message(textes.t("msg.tpl.noLine", a.nom()), true), model);
        }
        List<AllergiesRepository.Allergie> connues = allergies.liste(c.patientId());
        List<String> alertes = new ArrayList<>();
        contenu.ifPresent(m -> m.lignes().stream().filter(l -> lignes.contains(l.id())).forEach(l -> {
            List<String> concernees = AllergiesRepository.concernees(connues, l.medicament(), l.dci());
            if (!concernees.isEmpty()) alertes.add(l.libelle() + " (" + String.join(", ", concernees) + ")");
        }));
        String texte = a.ajoutees() == 0
                ? textes.t("msg.tpl.nothingAdded")
                : textes.t("msg.tpl.applied", a.nom(), a.ajoutees())
                + (a.ignorees() > 0 ? textes.t("msg.tpl.ignored", a.ignorees()) : "") + ".";
        if (!alertes.isEmpty()) texte += " " + textes.t("msg.tpl.allergy", String.join(" ; ", alertes));
        if (a.ajoutees() > 0) audit.log("ORDONNANCE_TYPE_APPLIQUEE", "consultation", id);
        return ordonnanceAJour(id, new Message(texte, a.ajoutees() == 0 || !alertes.isEmpty()), model);
    }

    @PostMapping("/consultations/{id}/ordonnance/modeles/{modele}/supprimer")
    public String supprimerModele(@PathVariable UUID id, @PathVariable UUID modele, Model model) {
        boolean ok = modeles.supprimer(modele);
        return panneauModeles(id, new Message(ok ? textes.t("msg.tpl.deleted") : textes.t("msg.tpl.gone"), !ok), model);
    }

    // ─────────────────────────────────────────────────────────────────────

    private String panneauModeles(UUID id, Message message, Model model) {
        model.addAttribute("consultation", reference(id));
        model.addAttribute("modeles", modeles.modeles());
        model.addAttribute("messageModeles", message);
        return "dossier/consultation :: modeles";
    }

    private String ordonnanceAJour(UUID id, Message message, Model model) {
        model.addAttribute("consultation", reference(id));
        model.addAttribute("lignes", ordonnances.lignes(id));
        model.addAttribute("alertes", alertes.ordonnance(id));
        model.addAttribute("precedente", ordonnances.precedenteAvecOrdonnance(id).orElse(null));
        model.addAttribute("messageOrdonnance", message);
        return "dossier/consultation :: ordonnance";
    }

    private ConsultationRepository.Consultation trouver(UUID id) {
        return consultations.trouver(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static ConsultationRepository.Consultation reference(UUID id) {
        return new ConsultationRepository.Consultation(id, null, null);
    }

    private static String borne(String s) { return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s; }

    /** Champs du formulaire : c_{type} (champ clinique), s_{type} (constante simple), x_{type}_{clé} (complexe). */
    static ConsultationRepository.Saisie saisie(Map<String, String> f) {
        ConsultationRepository.Saisie s = ConsultationRepository.Saisie.vide();
        f.forEach((nom, valeur) -> {
            try {
                if (nom.startsWith("c_")) s.champs().put(Long.parseLong(nom.substring(2)), borneTexte(valeur));
                else if (nom.startsWith("s_")) s.simples().put(Long.parseLong(nom.substring(2)), borne(valeur));
                else if (nom.startsWith("x_")) {
                    String[] p = nom.split("_", 3);
                    if (p.length == 3) s.complexes().computeIfAbsent(Long.parseLong(p[1]), k -> new HashMap<>()).put(p[2], borne(valeur));
                }
            } catch (NumberFormatException ignore) {
                // champ inconnu : ignoré
            }
        });
        return s;
    }

    private static String borneTexte(String s) { return s == null ? "" : s.length() > 10000 ? s.substring(0, 10000) : s; }
}
