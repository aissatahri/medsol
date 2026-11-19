package com.medsol.reservation;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.security.Totp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.text.Normalizer;
import java.util.Map;
import java.util.Set;

/**
 * Paramètres › Réservation en ligne (médecin) : activer la page publique, son adresse, la durée des créneaux, le
 * délai minimal et l'horizon ouverts, un message aux patients. Affiche le lien et son QR code (vitrine, cartes).
 */
@Controller
@RequestMapping("/settings/reservation")
public class ReservationSettingsController {

    public record Reglages(boolean active, String lien, int duree, int delai, int horizon, String message) {}

    private static final Set<String> RESERVES = Set.of("annuler");

    private final JdbcTemplate jdbc;
    private final CabinetService cabinets;
    private final AuditService audit;
    private final String baseUrl;

    public ReservationSettingsController(JdbcTemplate jdbc, CabinetService cabinets, AuditService audit,
                                         @Value("${medsol.base-url:}") String baseUrl) {
        this.jdbc = jdbc;
        this.cabinets = cabinets;
        this.audit = audit;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @GetMapping
    public String page(Model model) {
        Reglages r = jdbc.queryForObject("""
                SELECT reservation_active, reservation_lien, reservation_duree, reservation_delai, reservation_horizon, reservation_message
                FROM cabinet""", (rs, i) -> new Reglages(rs.getBoolean(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getString(6)));
        String lien = r.lien() != null ? r.lien() : propose(cabinets.current().nom());
        String adresse = base() + "/r/" + lien;
        model.addAttribute("r", r);
        model.addAttribute("lienPropose", lien);
        model.addAttribute("adresse", adresse);
        model.addAttribute("qr", r.active() && r.lien() != null ? Totp.qrSvg(adresse) : null);
        model.addAttribute("sansHoraires", jdbc.queryForObject("SELECT count(*) FROM disponibilite", Long.class) == 0);
        model.addAttribute("enAttente", jdbc.queryForObject("SELECT count(*) FROM reservation WHERE statut = 'EN_ATTENTE' AND debut > now()", Long.class));
        return "settings/reservation";
    }

    @PostMapping
    public String enregistrer(@RequestParam(defaultValue = "false") boolean active, @RequestParam String lien,
                              @RequestParam int duree, @RequestParam int delai, @RequestParam int horizon,
                              @RequestParam(defaultValue = "") String message, RedirectAttributes ra) {
        String l = lien.strip().toLowerCase();
        if (!l.matches("[a-z0-9][a-z0-9-]{2,59}") || RESERVES.contains(l)) {
            ra.addFlashAttribute("error", "booking.link.invalid");
        } else if (duree < 5 || duree > 120 || delai < 0 || delai > 168 || horizon < 1 || horizon > 180 || message.length() > 500) {
            ra.addFlashAttribute("error", "users.invalid");
        } else {
            try {
                jdbc.update("""
                        UPDATE cabinet SET reservation_active = ?, reservation_lien = ?, reservation_duree = ?, reservation_delai = ?,
                               reservation_horizon = ?, reservation_message = ?""",
                        active, l, duree, delai, horizon, message.isBlank() ? null : message.strip());
                audit.log("RESERVATION_REGLAGES", "cabinet", com.medsol.tenant.TenantContext.get());
                ra.addFlashAttribute("done", "settings.saved");
            } catch (DataIntegrityViolationException e) {
                ra.addFlashAttribute("error", "booking.link.taken");   // adresse déjà prise par un autre cabinet
            }
        }
        return "redirect:/settings/reservation";
    }

    /** « Cabinet de cardiologie Dr Benali » → « cabinet-de-cardiologie-dr-benali » (60 caractères au plus). */
    static String propose(String nom) {
        String s = Normalizer.normalize(nom == null ? "" : nom, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.length() > 60) s = s.substring(0, 60).replaceAll("-+$", "");
        return s.length() < 3 ? "cabinet-" + s : s;
    }

    private String base() {
        return baseUrl.isBlank() ? ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() : baseUrl;
    }
}
