package com.medsol.caisse;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.caisse.CaisseRepository.Ligne;
import com.medsol.caisse.CaisseRepository.TotalMode;
import com.medsol.security.MedsolUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** Caisse reprise de SGCM-Cardio : consultations du jour (ou impayées), versements, encaissement, annulation, totaux. */
@Controller
@RequestMapping("/caisse")
public class CaisseController {

    private static final DateTimeFormatter COURT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CaisseRepository caisse;
    private final CabinetService cabinets;
    private final AuditService audit;

    private final com.medsol.web.Textes textes;

    public CaisseController(CaisseRepository caisse, CabinetService cabinets, AuditService audit, com.medsol.web.Textes textes) {
        this.textes = textes;
        this.caisse = caisse;
        this.cabinets = cabinets;
        this.audit = audit;
    }

    @GetMapping
    public String page(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate jour,
                       @RequestParam(defaultValue = "false") boolean impayes, Model model) {
        CabinetService.Cabinet cab = cabinets.current();
        LocalDate j = jour == null ? cab.today() : jour;
        List<Ligne> liste = impayes ? caisse.impayes() : caisse.jour(j);
        List<TotalMode> parMode = caisse.encaisseLe(j);
        model.addAttribute("jour", j);
        model.addAttribute("jourTexte", j.format(COURT));
        model.addAttribute("impayes", impayes);
        model.addAttribute("lignes", liste);
        model.addAttribute("versements", caisse.versements(liste.stream().map(Ligne::consultationId).toList()));
        model.addAttribute("parMode", parMode);
        model.addAttribute("encaisse", parMode.stream().map(TotalMode::montant).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("du", liste.stream().map(Ligne::reste).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("devise", cab.devise());
        model.addAttribute("modes", CaisseRepository.MODES);
        return "caisse/page";
    }

    @PostMapping("/{consultation}/encaisser")
    public String encaisser(@PathVariable UUID consultation, @RequestParam String montant, @RequestParam String mode,
                            @RequestParam(required = false) String retour, @AuthenticationPrincipal MedsolUser user,
                            RedirectAttributes flash) {
        BigDecimal m;
        try {
            m = new BigDecimal(montant.strip().replace(',', '.').replace(" ", ""));
        } catch (NumberFormatException e) {
            m = BigDecimal.ZERO;
        }
        CabinetService.Cabinet cab = cabinets.current();
        String erreur = CaisseRepository.MODES.contains(mode)
                ? caisse.encaisser(consultation, Monnaie.arrondi(m), mode, cab.today(), user.id()) : "msg.cash.unknownMode";
        if (erreur == null) {
            audit.log("ENCAISSEMENT", "consultation", consultation);
            flash.addFlashAttribute("succes", textes.t("msg.cash.collected", Monnaie.format(m, cab.devise()), textes.t("cash.mode." + mode)));
        } else {
            // erreur du dépôt : « clé » ou « clé|paramètre »
            String[] e = erreur.split("\\|", 2);
            flash.addFlashAttribute("erreur", e.length == 2 ? textes.t(e[0], e[1]) : textes.t(e[0]));
        }
        return "redirect:" + retourSur(retour);
    }

    @PostMapping("/{consultation}/versements/{versement}/annuler")
    public String annuler(@PathVariable UUID consultation, @PathVariable UUID versement, @RequestParam(required = false) String retour,
                          @AuthenticationPrincipal MedsolUser user, RedirectAttributes flash) {
        if (caisse.annuler(consultation, versement, user.id())) {
            audit.log("VERSEMENT_ANNULE", "consultation", consultation);
            flash.addFlashAttribute("succes", textes.t("msg.cash.cancelled"));
        } else {
            flash.addFlashAttribute("erreur", textes.t("msg.cash.paymentGone"));
        }
        return "redirect:" + retourSur(retour);
    }

    /** Retour sur la caisse ou le dossier seulement (jamais vers une adresse fournie de l'extérieur). */
    private static String retourSur(String retour) {
        return retour != null && (retour.startsWith("/caisse") || retour.startsWith("/patients/")) && !retour.contains("//") ? retour : "/caisse";
    }
}
