package com.medsol.appointment;

import com.medsol.appointment.RendezVousRepository.Plage;
import com.medsol.appointment.RendezVousRepository.Rdv;
import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.patient.PatientRepository;
import com.medsol.queue.QueueRepository;
import com.medsol.security.MedsolUser;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Planning repris de PlanningController de SGCM-Cardio Web : calendrier jour / semaine / mois / liste (FullCalendar),
 * tiroir du rendez-vous, transitions et journal du bureau, report par glisser-déposer, horaires du cabinet.
 * Les actions renvoient le panneau et demandent au calendrier de se relire (en-tête HX-Trigger « rdvChange »).
 */
@Controller
@RequestMapping("/planning")
public class PlanningController {

    public static final Map<String, String[]> COULEURS = Map.of(
            "PLANIFIE", new String[]{"#64748b", "#ffffff"},
            "CONFIRME", new String[]{"#1d4ed8", "#ffffff"},
            "EN_COURS", new String[]{"#047857", "#ffffff"},
            "TERMINE", new String[]{"#a7f3d0", "#065f46"},
            "REPORTE", new String[]{"#f59e0b", "#1f2937"},
            "ANNULE", new String[]{"#fecaca", "#991b1b"},
            "ABSENT", new String[]{"#e5e7eb", "#6b7280"});
    public static final List<String> STATUTS = List.of("PLANIFIE", "CONFIRME", "EN_COURS", "TERMINE", "REPORTE", "ANNULE", "ABSENT");
    private static final Set<String> MANUELS = Set.of("CONFIRME", "ANNULE", "ABSENT");
    private static final DateTimeFormatter COURT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DH = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final RendezVousRepository rdv;
    private final PatientRepository patients;
    private final QueueRepository salle;
    private final CabinetService cabinets;
    private final AuditService audit;

    private final com.medsol.web.Textes textes;

    public PlanningController(RendezVousRepository rdv, PatientRepository patients, QueueRepository salle,
                              CabinetService cabinets, AuditService audit, com.medsol.web.Textes textes) {
        this.textes = textes;
        this.rdv = rdv;
        this.patients = patients;
        this.salle = salle;
        this.cabinets = cabinets;
        this.audit = audit;
    }

    @GetMapping
    public String page(Model model) {
        List<Plage> plages = rdv.plages();
        LocalTime premier = plages.stream().map(Plage::debut).min(LocalTime::compareTo).orElse(LocalTime.of(8, 0));
        LocalTime dernier = plages.stream().map(Plage::fin).max(LocalTime::compareTo).orElse(LocalTime.of(20, 0));
        Set<Integer> ouverts = plages.stream().map(p -> p.jour().getValue() % 7).collect(Collectors.toSet());
        List<Integer> fermes = plages.isEmpty() ? List.of() : IntStream.range(0, 7).filter(j -> !ouverts.contains(j)).boxed().toList();
        // Format attendu par le calendrier (businessHours) ; valeurs sûres (jours et heures), écrites à la main
        model.addAttribute("horairesJson", plages.stream()
                .map(p -> "{\"daysOfWeek\":[" + p.jour().getValue() % 7 + "],\"startTime\":\"" + p.debut() + "\",\"endTime\":\"" + p.fin() + "\"}")
                .collect(Collectors.joining(",", "[", "]")));
        model.addAttribute("joursFermes", fermes.toString());
        model.addAttribute("horairesDefinis", !plages.isEmpty());
        model.addAttribute("heureMin", premier.withMinute(0).toString());
        model.addAttribute("heureMax", dernier.getMinute() == 0 ? dernier.toString()
                : dernier.getHour() >= 23 ? "24:00" : dernier.withMinute(0).plusHours(1).toString());
        model.addAttribute("horairesTexte", texte(plages));
        model.addAttribute("couleurs", COULEURS);
        return "planning/planning";
    }

    /** Rendez-vous de la période affichée par le calendrier, en une seule requête. */
    @GetMapping("/evenements")
    @ResponseBody
    public List<Map<String, Object>> evenements(@RequestParam String start, @RequestParam String end,
                                                @RequestParam(required = false) List<String> statuts) {
        LocalDate debut = date(start);
        LocalDate fin = date(end);
        if (Duration.between(debut.atStartOfDay(), fin.atStartOfDay()).toDays() > 62) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Période trop longue");
        }
        Set<String> filtre = statuts == null || statuts.isEmpty() ? Set.copyOf(STATUTS) : Set.copyOf(statuts);
        List<Map<String, Object>> liste = new ArrayList<>();
        for (Rdv r : rdv.entre(debut, fin.minusDays(1))) {
            if (!filtre.contains(r.statut())) continue;
            String[] c = COULEURS.getOrDefault(r.statut(), COULEURS.get("PLANIFIE"));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", r.id().toString());
            e.put("title", r.nomComplet() + (r.titre() == null || r.titre().isBlank() ? "" : " · " + r.titre()));
            e.put("start", r.date().atTime(r.debut()).toString());
            e.put("end", r.date().atTime(r.fin().isAfter(r.debut()) ? r.fin() : r.debut().plusMinutes(30)).toString());
            e.put("backgroundColor", c[0]);
            e.put("borderColor", c[0]);
            e.put("textColor", c[1]);
            // Un rendez-vous en cours ou terminé ne se déplace plus (il suit la salle d'attente)
            e.put("editable", r.peut("REPORTE"));
            e.put("classNames", List.of("rdv-" + r.statut().toLowerCase()));
            e.put("extendedProps", Map.of("statut", r.statut(), "libelle", r.statutLibelle()));
            liste.add(e);
        }
        return liste;
    }

    /** Chiffres de la période (indicateurs du bureau). */
    @GetMapping("/indicateurs")
    public String indicateurs(@RequestParam String start, @RequestParam String end, Model model) {
        List<Rdv> liste = rdv.entre(date(start), date(end).minusDays(1));
        Map<String, Long> parStatut = new LinkedHashMap<>();
        STATUTS.forEach(s -> parStatut.put(s, 0L));
        liste.forEach(r -> parStatut.merge(r.statut(), 1L, Long::sum));
        long total = liste.size();
        model.addAttribute("total", total);
        model.addAttribute("parStatut", parStatut);
        model.addAttribute("libelles", STATUTS.stream().collect(Collectors.toMap(s -> s, RendezVousRepository::libelle)));
        model.addAttribute("couleurs", COULEURS);
        model.addAttribute("tauxAnnulation", total == 0 ? 0 : Math.round(100.0 * parStatut.get("ANNULE") / total));
        model.addAttribute("tauxAbsence", total == 0 ? 0 : Math.round(100.0 * parStatut.get("ABSENT") / total));
        return "planning/planning :: indicateurs";
    }

    @GetMapping("/{id}")
    public String panneau(@PathVariable UUID id, Model model) {
        return panneau(id, null, false, model);
    }

    /** Formulaire de création, prérempli avec le créneau cliqué. */
    @GetMapping("/nouveau")
    public String nouveau(@RequestParam(required = false) String debut, @RequestParam(required = false) String fin, Model model) {
        LocalDateTime d = debut == null ? prochaineOuverture(rdv.plages()) : dateHeure(debut);
        LocalDateTime f = fin == null ? d.plusMinutes(30) : dateHeure(fin);
        if (d.toLocalTime().equals(LocalTime.MIDNIGHT) && f.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            d = d.toLocalDate().atTime(9, 0);   // clic sur un jour (vue mois) : 9 h par défaut
            f = d.plusMinutes(30);
        }
        model.addAttribute("date", d.toLocalDate());
        model.addAttribute("heure", d.toLocalTime());
        model.addAttribute("duree", Math.max(15, Duration.between(d, f).toMinutes()));
        return "planning/planning :: nouveau";
    }

    @GetMapping("/patients")
    public String patients(@RequestParam(defaultValue = "") String q, Model model) {
        model.addAttribute("trouves", q.strip().length() < 2 ? List.of() : patients.search(q, 0, 8).items());
        model.addAttribute("q", q.strip());
        return "planning/planning :: trouves";
    }

    @GetMapping("/patient/{id}")
    public String patientChoisi(@PathVariable UUID id, Model model) {
        model.addAttribute("choisi", patients.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "planning/planning :: choisi";
    }

    @PostMapping
    public String creer(@RequestParam(required = false) UUID patientId, @RequestParam String date, @RequestParam String heure,
                        @RequestParam(defaultValue = "30") int duree, @RequestParam(defaultValue = "") String titre,
                        @RequestParam(defaultValue = "false") boolean forcer, @AuthenticationPrincipal MedsolUser user,
                        HttpServletResponse reponse, Model model) {
        LocalDate d = jour(date);
        LocalTime h = heure(heure);
        String t = titre.length() > 200 ? titre.substring(0, 200) : titre;
        model.addAttribute("date", d);
        model.addAttribute("heure", h);
        model.addAttribute("duree", duree);
        model.addAttribute("titre", t);
        if (patientId == null || patients.find(patientId).isEmpty()) {
            model.addAttribute("erreur", textes.t("msg.rdv.patientRequired"));
            return "planning/planning :: nouveau";
        }
        LocalTime fin = h.plusMinutes(Math.max(5, Math.min(duree, 240)));
        if (fin.isBefore(h)) fin = LocalTime.of(23, 59);
        String hors = horsHoraires(rdv.plages(), d, h, fin, textes);
        List<Rdv> conflits = rdv.chevauchements(d, h, fin, null);
        if ((hors != null || !conflits.isEmpty()) && !forcer) {
            model.addAttribute("choisi", patients.find(patientId).orElse(null));
            model.addAttribute("erreur", (hors != null ? hors : textes.t("msg.rdv.slotTaken", conflits.stream()
                    .map(c -> c.debut() + " " + c.nomComplet()).collect(Collectors.joining(", "))))
                    + " " + textes.t("msg.rdv.forceHint"));
            return "planning/planning :: nouveau";
        }
        UUID id = rdv.creer(patientId, user.id(), d, h, fin, t, user.fullName());
        audit.log("RDV_CREATION", "rendez_vous", id);
        reponse.setHeader("HX-Trigger", "rdvChange");
        model.addAttribute("message", textes.t("msg.rdv.created", d.format(COURT), h.toString()));
        return "planning/planning :: confirmation";
    }

    @PostMapping("/{id}/statut")
    public String statut(@PathVariable UUID id, @RequestParam String attendu, @RequestParam String nouveau,
                         @AuthenticationPrincipal MedsolUser user, HttpServletResponse reponse, Model model) {
        Rdv affiche = trouver(id).avecStatut(attendu);
        String message;
        boolean erreur = true;
        if (!MANUELS.contains(nouveau) || !affiche.peut(nouveau)) {
            message = textes.t("msg.rdv.transitionRefused", textes.t("appointment.status." + attendu), textes.t("appointment.status." + nouveau));
        } else if (rdv.changer(affiche, nouveau, null, null, null, user.fullName(), null)) {
            audit.log("RDV_" + nouveau, "rendez_vous", id);
            message = textes.t("msg.rdv.statusDone", textes.t("appointment.status." + nouveau).toLowerCase());
            erreur = false;
        } else {
            message = textes.t("msg.rdv.stale");
        }
        reponse.setHeader("HX-Trigger", "rdvChange");
        return panneau(id, message, erreur, model);
    }

    @PostMapping("/{id}/reporter")
    public String reporter(@PathVariable UUID id, @RequestParam String attendu, @RequestParam String date, @RequestParam String heure,
                           @RequestParam(defaultValue = "") String motif, @AuthenticationPrincipal MedsolUser user,
                           HttpServletResponse reponse, Model model) {
        LocalDate d = jour(date);
        LocalTime h = heure(heure);
        String message = deplacer(trouver(id), attendu, d, h, null, motif, user, false);
        reponse.setHeader("HX-Trigger", "rdvChange");
        return panneau(id, message == null ? textes.t("msg.rdv.postponed", d.format(COURT), h.toString()) : message, message != null, model);
    }

    /** Glisser-déposer ou étirement dans le calendrier : c'est un report (journal), refusé si le créneau est pris. */
    @PostMapping("/{id}/deplacer")
    @ResponseBody
    public Map<String, Object> deplacer(@PathVariable UUID id, @RequestParam String attendu, @RequestParam String debut,
                                        @RequestParam String fin, @AuthenticationPrincipal MedsolUser user) {
        Rdv r = rdv.trouver(id).orElse(null);
        if (r == null) return Map.of("ok", false, "message", textes.t("msg.rdv.gone"));
        LocalDateTime d = dateHeure(debut);
        LocalDateTime f = dateHeure(fin);
        if (!f.toLocalDate().equals(d.toLocalDate()) || !f.isAfter(d)) {
            return Map.of("ok", false, "message", textes.t("msg.rdv.sameDay"));
        }
        String erreur = deplacer(r, attendu, d.toLocalDate(), d.toLocalTime(), f.toLocalTime(), "", user, true);
        return erreur == null ? Map.of("ok", true, "message", textes.t("msg.rdv.moved", r.nomComplet(), d.format(DH)))
                : Map.of("ok", false, "message", erreur);
    }

    /** Le patient est arrivé : il entre dans la salle d'attente et le rendez-vous passe « En cours ». */
    @PostMapping("/{id}/arrive")
    public String arrive(@PathVariable UUID id, HttpServletResponse reponse, Model model) {
        Rdv r = trouver(id);
        boolean ajoute = salle.add(r.patientId(), r.titre(), r.id(), cabinets.current().today());
        if (ajoute) audit.log("RDV_ARRIVEE", "rendez_vous", id);
        reponse.setHeader("HX-Trigger", "rdvChange");
        return panneau(id, ajoute ? textes.t("msg.rdv.toQueue") : textes.t("msg.rdv.alreadyInQueue"), !ajoute, model);
    }

    // ─────────────────────────────────────────────────────────────────────

    /** @return null si le report est fait, sinon le message d'erreur */
    private String deplacer(Rdv r, String attendu, LocalDate date, LocalTime debut, LocalTime fin, String motif,
                            MedsolUser user, boolean controlerCreneau) {
        Rdv affiche = r.avecStatut(attendu);
        if (!affiche.peut("REPORTE")) {
            return textes.t("msg.rdv.cannotMove", textes.t("appointment.status." + affiche.statut()).toLowerCase());
        }
        long minutes = Duration.between(r.debut(), r.fin()).toMinutes();
        LocalTime f = fin != null ? fin : debut.plusMinutes(minutes > 0 ? minutes : 30);
        String hors = horsHoraires(rdv.plages(), date, debut, f, textes);
        if (hors != null) return hors;
        if (controlerCreneau && !rdv.chevauchements(date, debut, f, r.id()).isEmpty()) return textes.t("msg.rdv.slotBusy");
        String trajet = r.date().atTime(r.debut()).format(DH) + " → " + date.atTime(debut).format(DH);
        boolean ok = rdv.changer(affiche, "REPORTE", date, debut, f, user.fullName(),
                motif == null || motif.isBlank() ? trajet : trajet + " : " + motif.strip());
        if (ok) audit.log("RDV_REPORTE", "rendez_vous", r.id());
        return ok ? null : textes.t("msg.rdv.staleRefreshed");
    }

    /** Début de la prochaine plage d'ouverture (aujourd'hui si elle n'est pas finie), 9 h sans horaires définis. */
    private LocalDateTime prochaineOuverture(List<Plage> plages) {
        CabinetService.Cabinet cab = cabinets.current();
        LocalDateTime maintenant = LocalDateTime.now(cab.zone());
        if (plages.isEmpty()) return cab.today().atTime(9, 0);
        for (int i = 0; i < 8; i++) {
            LocalDate j = maintenant.toLocalDate().plusDays(i);
            for (Plage p : plages) {
                if (p.jour() != j.getDayOfWeek() || !j.atTime(p.fin()).isAfter(maintenant)) continue;
                LocalDateTime debut = j.atTime(p.debut());
                if (debut.isBefore(maintenant)) {
                    LocalTime t = maintenant.toLocalTime().withSecond(0).withNano(0);
                    debut = j.atTime(t.plusMinutes((15 - t.getMinute() % 15) % 15));   // quart d'heure suivant
                }
                if (!debut.toLocalTime().plusMinutes(15).isAfter(p.fin())) return debut;
            }
        }
        return cab.today().atTime(9, 0);
    }

    /** @return null si le créneau est dans une plage du cabinet (ou si aucune plage n'est définie), sinon le message */
    static String horsHoraires(List<Plage> plages, LocalDate date, LocalTime debut, LocalTime fin, com.medsol.web.Textes textes) {
        if (plages.isEmpty()) return null;
        DayOfWeek jour = date.getDayOfWeek();
        List<Plage> duJour = plages.stream().filter(p -> p.jour() == jour).toList();
        String nom = jour.getDisplayName(TextStyle.FULL, org.springframework.context.i18n.LocaleContextHolder.getLocale());
        if (duJour.isEmpty()) return textes.t("msg.rdv.closed", nom);
        boolean dedans = duJour.stream().anyMatch(p -> !debut.isBefore(p.debut()) && !fin.isAfter(p.fin()));
        return dedans ? null : textes.t("msg.rdv.outsideHours", nom, plagesTexte(duJour));
    }

    /** « lun.–ven. 09:00–13:00, 15:00–19:00 · sam. 09:00–12:00 » : jours consécutifs aux mêmes horaires regroupés. */
    static String texte(List<Plage> plages) {
        Map<DayOfWeek, String> parJour = new TreeMap<>();
        plages.stream().collect(Collectors.groupingBy(Plage::jour)).forEach((j, l) -> parJour.put(j, plagesTexte(l)));
        List<String> morceaux = new ArrayList<>();
        DayOfWeek debutGroupe = null;
        DayOfWeek precedent = null;
        for (Map.Entry<DayOfWeek, String> e : parJour.entrySet()) {
            boolean suite = precedent != null && e.getKey().getValue() == precedent.getValue() + 1 && e.getValue().equals(parJour.get(precedent));
            if (!suite && debutGroupe != null) morceaux.add(groupe(debutGroupe, precedent, parJour.get(precedent)));
            if (!suite) debutGroupe = e.getKey();
            precedent = e.getKey();
        }
        if (debutGroupe != null) morceaux.add(groupe(debutGroupe, precedent, parJour.get(precedent)));
        return String.join(" · ", morceaux);
    }

    private static String groupe(DayOfWeek de, DayOfWeek a, String heures) {
        Locale l = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        String nom = de.getDisplayName(TextStyle.SHORT, l);
        return (de == a ? nom : nom + "–" + a.getDisplayName(TextStyle.SHORT, l)) + " " + heures;
    }

    private static String plagesTexte(List<Plage> l) {
        return l.stream().sorted(Comparator.comparing(Plage::debut)).map(p -> p.debut() + "–" + p.fin()).collect(Collectors.joining(", "));
    }

    private String panneau(UUID id, String message, boolean erreur, Model model) {
        model.addAttribute("r", trouver(id));
        model.addAttribute("journal", rdv.journal(id));
        model.addAttribute("aujourdhui", cabinets.current().today());
        model.addAttribute("message", message);
        model.addAttribute("erreurPanneau", erreur);
        return "planning/planning :: panneau";
    }

    private Rdv trouver(UUID id) {
        return rdv.trouver(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static LocalDate jour(String s) {
        try { return LocalDate.parse(s); } catch (DateTimeParseException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date illisible"); }
    }

    private static LocalTime heure(String s) {
        try { return LocalTime.parse(s); } catch (DateTimeParseException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Heure illisible"); }
    }

    private static LocalDate date(String texte) { return dateHeure(texte).toLocalDate(); }

    /** Dates envoyées par le calendrier : « 2026-11-09 », « 2026-11-09T08:30:00 » ou avec décalage horaire. */
    private static LocalDateTime dateHeure(String texte) {
        try {
            if (texte.length() == 10) return LocalDate.parse(texte).atStartOfDay();
            if (texte.length() > 19) return OffsetDateTime.parse(texte).toLocalDateTime();
            return LocalDateTime.parse(texte);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date illisible");
        }
    }
}
