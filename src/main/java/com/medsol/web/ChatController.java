package com.medsol.web;

import com.medsol.cabinet.CabinetService;
import com.medsol.patient.PatientRepository;
import com.medsol.security.MedsolUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Messagerie du cabinet, médecin ↔ assistante (reprise de ChatController de SGCM-Cardio). La page interroge le
 * serveur toutes les quelques secondes (app.js) ; un patient peut être joint au message ([[patient:id|Nom]]).
 * Les textes sont affichés comme du texte, jamais comme du HTML.
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM");

    private final JdbcTemplate jdbc;
    private final CabinetService cabinets;
    private final PatientRepository patients;

    public ChatController(JdbcTemplate jdbc, CabinetService cabinets, PatientRepository patients) {
        this.jdbc = jdbc;
        this.cabinets = cabinets;
        this.patients = patients;
    }

    /** Messages postérieurs à {@code apres} (les 50 derniers au premier appel), non lus et dernier numéro. */
    @GetMapping("/messages")
    public Map<String, Object> messages(@RequestParam(defaultValue = "0") long apres, @AuthenticationPrincipal MedsolUser user) {
        var zone = cabinets.current().zone();
        var aujourdhui = cabinets.current().today();
        List<Map<String, Object>> l = new ArrayList<>(jdbc.query("""
                SELECT m.id, m.auteur_id, u.nom_complet, m.texte, m.cree_le FROM message m JOIN utilisateur u ON u.id = m.auteur_id
                WHERE m.id > ? ORDER BY m.id DESC LIMIT 50""", (rs, i) -> {
            ZonedDateTime le = rs.getTimestamp(5).toInstant().atZone(zone);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong(1));
            m.put("miens", user.id().equals(rs.getObject(2, UUID.class)));
            m.put("nom", rs.getString(3));
            m.put("texte", rs.getString(4));
            m.put("jour", le.toLocalDate().equals(aujourdhui) ? "" : le.format(JOUR));
            m.put("heure", le.format(HEURE));
            return m;
        }, apres));
        Collections.reverse(l);
        Long dernierLu = jdbc.query("SELECT dernier FROM message_lu WHERE utilisateur_id = ?", (rs, i) -> rs.getLong(1), user.id())
                .stream().findFirst().orElse(0L);
        Long nonLus = jdbc.queryForObject("SELECT count(*) FROM message WHERE id > ? AND auteur_id <> ?", Long.class, dernierLu, user.id());
        Long dernier = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM message", Long.class);
        return Map.of("messages", l, "nonLus", nonLus, "dernier", dernier);
    }

    @PostMapping("/messages")
    public Map<String, Object> envoyer(@RequestParam String texte, @AuthenticationPrincipal MedsolUser user) {
        String t = texte.strip();
        if (t.isEmpty() || t.length() > 900) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message vide ou trop long");
        long id = jdbc.queryForObject("INSERT INTO message (cabinet_id, auteur_id, texte) VALUES (current_cabinet(), ?, ?) RETURNING id",
                Long.class, user.id(), t);
        var maintenant = ZonedDateTime.now(cabinets.current().zone());
        lu(user, id);
        return Map.of("id", id, "miens", true, "nom", user.fullName(), "texte", t, "jour", "", "heure", maintenant.format(HEURE));
    }

    @PostMapping("/lus")
    public Map<String, Object> lus(@AuthenticationPrincipal MedsolUser user) {
        Long dernier = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM message", Long.class);
        lu(user, dernier);
        return Map.of("ok", true);
    }

    /** Patients à joindre au message. */
    @GetMapping("/patients")
    public List<Map<String, Object>> patients(@RequestParam(defaultValue = "") String q) {
        if (q.strip().length() < 2) return List.of();
        return patients.search(q, 0, 8).items().stream()
                .map(p -> Map.<String, Object>of("id", p.id().toString(), "nom", p.nomComplet())).toList();
    }

    private void lu(MedsolUser user, long dernier) {
        jdbc.update("""
                INSERT INTO message_lu (utilisateur_id, cabinet_id, dernier) VALUES (?, current_cabinet(), ?)
                ON CONFLICT (utilisateur_id) DO UPDATE SET dernier = greatest(message_lu.dernier, excluded.dernier)""", user.id(), dernier);
    }
}
