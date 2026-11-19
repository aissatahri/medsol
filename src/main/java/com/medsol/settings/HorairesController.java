package com.medsol.settings;

import com.medsol.appointment.RendezVousRepository;
import com.medsol.appointment.RendezVousRepository.Plage;
import com.medsol.audit.AuditService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Horaires de consultation du cabinet (Disponibilités du bureau) : deux plages par jour au plus. Médecin. */
@Controller
@RequestMapping("/settings/horaires")
public class HorairesController {

    public record Jour(int numero, String nom, String debut1, String fin1, String debut2, String fin2) {}

    private static final String[] NOMS = {"Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche"};

    private final RendezVousRepository rdv;
    private final AuditService audit;

    public HorairesController(RendezVousRepository rdv, AuditService audit) {
        this.rdv = rdv;
        this.audit = audit;
    }

    @GetMapping
    public String page(Model model) {
        Map<DayOfWeek, List<Plage>> parJour = new EnumMap<>(DayOfWeek.class);
        rdv.plages().forEach(p -> parJour.computeIfAbsent(p.jour(), k -> new ArrayList<>()).add(p));
        List<Jour> jours = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            List<Plage> l = parJour.getOrDefault(DayOfWeek.of(i), List.of());
            String nomJour = DayOfWeek.of(i).getDisplayName(java.time.format.TextStyle.FULL, org.springframework.context.i18n.LocaleContextHolder.getLocale());
            jours.add(new Jour(i, nomJour.substring(0, 1).toUpperCase() + nomJour.substring(1),
                    l.size() > 0 ? l.get(0).debut().toString() : "", l.size() > 0 ? l.get(0).fin().toString() : "",
                    l.size() > 1 ? l.get(1).debut().toString() : "", l.size() > 1 ? l.get(1).fin().toString() : ""));
        }
        model.addAttribute("jours", jours);
        return "settings/horaires";
    }

    @PostMapping
    public String enregistrer(@RequestParam Map<String, String> f, RedirectAttributes ra) {
        List<Plage> plages = new ArrayList<>();
        try {
            for (int i = 1; i <= 7; i++) {
                for (int k = 1; k <= 2; k++) {
                    String d = f.getOrDefault("d" + i + "_" + k, "").strip();
                    String e = f.getOrDefault("f" + i + "_" + k, "").strip();
                    if (d.isEmpty() && e.isEmpty()) continue;
                    LocalTime debut = LocalTime.parse(d);
                    LocalTime fin = LocalTime.parse(e);
                    if (!fin.isAfter(debut)) {
                        ra.addFlashAttribute("error", "horaires.invalid");
                        return "redirect:/settings/horaires";
                    }
                    plages.add(new Plage(DayOfWeek.of(i), debut, fin));
                }
            }
        } catch (DateTimeParseException ex) {
            ra.addFlashAttribute("error", "horaires.invalid");
            return "redirect:/settings/horaires";
        }
        rdv.remplacerPlages(plages);
        audit.log("HORAIRES_MODIFICATION", "cabinet", null);
        ra.addFlashAttribute("done", "settings.saved");
        return "redirect:/settings/horaires";
    }
}
