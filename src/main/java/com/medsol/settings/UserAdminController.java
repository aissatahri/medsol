package com.medsol.settings;

import com.medsol.audit.AuditService;
import com.medsol.cabinet.CabinetService;
import com.medsol.security.Jetons;
import com.medsol.security.LiensCompte;
import com.medsol.security.MedsolUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Comptes du cabinet (assistantes). Réservé au médecin (voir SecurityConfig). Création avec mot de passe ou par
 * invitation e-mail, droits réglables (caisse, résultats), réinitialisation et désactivation : ces deux dernières
 * ferment aussitôt les sessions ouvertes du compte (version_session).
 */
@Controller
@RequestMapping("/settings/users")
public class UserAdminController {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final Jetons jetons;
    private final LiensCompte liens;
    private final CabinetService cabinets;
    private final com.medsol.security.DoubleAuthentification deuxFacteurs;

    public UserAdminController(JdbcTemplate jdbc, PasswordEncoder encoder, AuditService audit, Jetons jetons,
                               LiensCompte liens, CabinetService cabinets, com.medsol.security.DoubleAuthentification deuxFacteurs) {
        this.deuxFacteurs = deuxFacteurs;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.jetons = jetons;
        this.liens = liens;
        this.cabinets = cabinets;
    }

    /** Compte du cabinet : dernière connexion lue dans le journal d'accès. */
    public record Compte(UUID id, String email, String nom, String role, boolean actif, boolean bloque,
                         java.time.Instant derniereConnexion, java.time.Instant creeLe, Set<String> droits, boolean totp,
                         boolean invitation) {
        public boolean peut(String droit) { return "MEDECIN".equals(role) || droits.contains(droit); }
    }

    @GetMapping
    public String list(Model model) {
        List<Compte> users = jdbc.query("""
                SELECT u.id, u.email, u.nom_complet, u.role, u.actif, coalesce(u.bloque_jusqua > now(), false), u.cree_le,
                       (SELECT max(j.quand) FROM journal_audit j WHERE j.utilisateur_id = u.id AND j.action = 'CONNEXION'),
                       u.droits, u.totp_actif
                FROM utilisateur u ORDER BY (u.role = 'MEDECIN') DESC, u.actif DESC, u.nom_complet""",
                (rs, i) -> new Compte(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                        rs.getBoolean(6), rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(), rs.getTimestamp(7).toInstant(),
                        Set.of((String[]) rs.getArray(9).getArray()), rs.getBoolean(10), false));
        Set<UUID> invites = jetons.invitationsEnAttente(users.stream().map(Compte::id).toList());
        model.addAttribute("users", users.stream().map(c -> invites.contains(c.id()) ? new Compte(c.id(), c.email(), c.nom(), c.role(),
                c.actif(), c.bloque(), c.derniereConnexion(), c.creeLe(), c.droits(), c.totp(), true) : c).toList());
        model.addAttribute("droitsPossibles", MedsolUser.DROITS);
        return "settings/users";
    }

    @PostMapping
    public String create(@RequestParam String email, @RequestParam String fullName, @RequestParam(defaultValue = "") String password,
                         @RequestParam(required = false) String confirmation, @RequestParam(defaultValue = "false") boolean inviter,
                         @RequestParam(required = false) List<String> droits, @RequestParam(defaultValue = "false") boolean reglerDroits,
                         @AuthenticationPrincipal MedsolUser me, RedirectAttributes ra) {
        email = email.trim();
        fullName = fullName.trim();
        if (fullName.isEmpty() || fullName.length() > 150 || !EMAIL.matcher(email).matches() || email.length() > 200) {
            ra.addFlashAttribute("error", "users.invalid");
        } else if (!inviter && (password.length() < 10 || password.length() > 100)) {
            ra.addFlashAttribute("error", "users.password.short");
        } else if (!inviter && confirmation != null && !confirmation.equals(password)) {
            ra.addFlashAttribute("error", "users.password.mismatch");
        } else if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT email_existe(?)", Boolean.class, email))) {
            ra.addFlashAttribute("error", "register.email.taken");
        } else {
            // invitation : mot de passe aléatoire inconnu de tous, remplacé quand l'assistante ouvre le lien
            String motDePasse = inviter ? aleatoire() : password;
            String langue = cabinets.current().langue();
            UUID id = jdbc.queryForObject("""
                    INSERT INTO utilisateur (cabinet_id, email, mot_de_passe, nom_complet, role, droits, langue)
                    VALUES (current_cabinet(), ?, ?, ?, 'SECRETAIRE', ?, ?) RETURNING id""",
                    UUID.class, email, encoder.encode(motDePasse), fullName, reglerDroits ? filtrer(droits) : MedsolUser.DROITS.toArray(new String[0]), com.medsol.web.Langues.ou_francais(langue));
            audit.log("UTILISATEUR_CREATION", "utilisateur", id);
            if (inviter) {
                liens.invitation(id, email, fullName, langue, cabinets.current().nom(), me.fullName());
                audit.log("UTILISATEUR_INVITATION", "utilisateur", id);
                ra.addFlashAttribute("done", "users.invited");
            } else {
                ra.addFlashAttribute("done", "users.created");
            }
        }
        return "redirect:/settings/users";
    }

    @PostMapping("/{id}/invitation")
    public String renvoyer(@PathVariable UUID id, @AuthenticationPrincipal MedsolUser me, RedirectAttributes ra) {
        List<String[]> c = jdbc.query("SELECT email, nom_complet, langue FROM utilisateur WHERE id = ? AND actif AND role <> 'MEDECIN'",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, id);
        if (!c.isEmpty()) {
            liens.invitation(id, c.get(0)[0], c.get(0)[1], c.get(0)[2], cabinets.current().nom(), me.fullName());
            audit.log("UTILISATEUR_INVITATION", "utilisateur", id);
            ra.addFlashAttribute("done", "users.invited");
        }
        return "redirect:/settings/users";
    }

    /** Droits de l'assistante : appliqués à sa prochaine requête, sans la déconnecter (SessionGuard). */
    @PostMapping("/{id}/droits")
    public String droits(@PathVariable UUID id, @RequestParam(required = false) List<String> droits, RedirectAttributes ra) {
        if (jdbc.update("UPDATE utilisateur SET droits = ? WHERE id = ? AND role <> 'MEDECIN'", filtrer(droits), id) == 1) {
            audit.log("UTILISATEUR_DROITS", "utilisateur", id);
            ra.addFlashAttribute("done", "users.rights.saved");
        }
        return "redirect:/settings/users";
    }

    /** Téléphone perdu : le médecin retire la double authentification d'une assistante (ses sessions sont fermées). */
    @PostMapping("/{id}/2fa")
    public String retirer2fa(@PathVariable UUID id, RedirectAttributes ra) {
        // appartenance au cabinet vérifiée par la RLS avant d'agir avec les connexions du propriétaire
        if (Boolean.TRUE.equals(jdbc.query("SELECT true FROM utilisateur WHERE id = ? AND role <> 'MEDECIN' AND totp_actif",
                (rs, i) -> true, id).stream().findFirst().orElse(false))) {
            deuxFacteurs.retirer(id, true);
            audit.log("DOUBLE_AUTH_RETIREE", "utilisateur", id);
            ra.addFlashAttribute("done", "users.2fa.removed");
        }
        return "redirect:/settings/users";
    }

    @PostMapping("/{id}/active")
    public String setActive(@PathVariable UUID id, @RequestParam boolean value,
                            @AuthenticationPrincipal MedsolUser me, RedirectAttributes ra) {
        if (id.equals(me.id())) {
            ra.addFlashAttribute("error", "users.self");
        } else if (jdbc.update("UPDATE utilisateur SET actif = ?, version_session = version_session + 1 WHERE id = ?", value, id) == 1) {
            audit.log(value ? "UTILISATEUR_ACTIVATION" : "UTILISATEUR_DESACTIVATION", "utilisateur", id);
            ra.addFlashAttribute("done", value ? "users.enabled" : "users.disabled.done");
        }
        return "redirect:/settings/users";
    }

    @PostMapping("/{id}/password")
    public String resetPassword(@PathVariable UUID id, @RequestParam String password,
                                @RequestParam(required = false) String confirmation,
                                @AuthenticationPrincipal MedsolUser me, RedirectAttributes ra) {
        if (id.equals(me.id())) {
            ra.addFlashAttribute("error", "users.self.password");
        } else if (password.length() < 10 || password.length() > 100) {
            ra.addFlashAttribute("error", "users.password.short");
        } else if (confirmation != null && !confirmation.equals(password)) {
            ra.addFlashAttribute("error", "users.password.mismatch");
        } else if (jdbc.update("""
                UPDATE utilisateur SET mot_de_passe = ?, echecs = 0, bloque_jusqua = NULL, version_session = version_session + 1
                WHERE id = ?""", encoder.encode(password), id) == 1) {
            audit.log("UTILISATEUR_MOT_DE_PASSE", "utilisateur", id);
            ra.addFlashAttribute("done", "users.password.changed");
        }
        return "redirect:/settings/users";
    }

    private static String[] filtrer(List<String> droits) {
        return droits == null ? new String[0] : droits.stream().filter(MedsolUser.DROITS::contains).distinct().toArray(String[]::new);
    }

    private static String aleatoire() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().encodeToString(b);
    }
}
