package com.medsol.reservation;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Côté patient de la prise de rendez-vous en ligne : cabinet public, créneaux libres, demande, annulation.
 * Pages sans compte : connexions du propriétaire, chaque requête filtrée explicitement sur le cabinet.
 * Un créneau est libre s'il est dans les horaires du cabinet, assez loin dans le futur (délai), dans l'horizon
 * ouvert, et ne chevauche ni un rendez-vous actif ni une demande en attente.
 */
@Service
public class ReservationPublique {

    public record Cabinet(UUID id, String nom, String specialite, String adresse, String telephone, ZoneId zone,
                          int duree, int delai, int horizon, String message, boolean horaires) {}

    public record Creneau(LocalDateTime debut, LocalDateTime fin) {}

    public record Demande(String nom, String prenom, String telephone, String email, String motif, String langue) {}

    public enum Resultat { ENREGISTREE, CRENEAU_PRIS, TROP_DE_DEMANDES }

    public record Reponse(Resultat resultat, String jeton) {}

    public record Annulable(UUID id, String cabinet, LocalDateTime debut, String statut) {}

    private static final SecureRandom HASARD = new SecureRandom();

    private final JdbcTemplate plateforme;

    public ReservationPublique(@Qualifier("plateforme") JdbcTemplate plateforme) { this.plateforme = plateforme; }

    /** Cabinet dont la réservation en ligne est active et l'abonnement en cours. */
    public Optional<Cabinet> cabinet(String lien, String langue) {
        if (lien == null || !lien.matches("[a-z0-9-]{3,60}")) return Optional.empty();
        return plateforme.query("""
                SELECT c.id, c.nom, coalesce(s.libelle->>?, s.libelle->>'fr'), c.adresse, c.telephone, c.fuseau,
                       c.reservation_duree, c.reservation_delai, c.reservation_horizon, c.reservation_message,
                       EXISTS (SELECT 1 FROM disponibilite d WHERE d.cabinet_id = c.id)
                FROM cabinet c JOIN specialite s ON s.code = c.specialite JOIN abonnement a ON a.cabinet_id = c.id
                WHERE c.reservation_lien = ? AND c.reservation_active AND a.statut <> 'SUSPENDU' AND a.fin >= current_date""",
                (rs, i) -> new Cabinet(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        ZoneId.of(rs.getString(6)), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getString(10), rs.getBoolean(11)),
                langue, lien).stream().findFirst();
    }

    /** Créneaux libres du jour {@code du} inclus, sur {@code jours} jours (bornés par l'horizon du cabinet). */
    public Map<LocalDate, List<Creneau>> creneaux(Cabinet c, LocalDate du, int jours) {
        LocalDateTime maintenant = LocalDateTime.now(c.zone());
        LocalDate dernier = maintenant.toLocalDate().plusDays(c.horizon());
        LocalDate fin = du.plusDays(jours);
        if (fin.isAfter(dernier.plusDays(1))) fin = dernier.plusDays(1);
        Map<LocalDate, List<Creneau>> r = new LinkedHashMap<>();
        if (!fin.isAfter(du)) return r;

        Map<DayOfWeek, List<LocalTime[]>> plages = new EnumMap<>(DayOfWeek.class);
        plateforme.query("SELECT jour, debut, fin FROM disponibilite WHERE cabinet_id = ? ORDER BY jour, debut",
                rs -> { plages.computeIfAbsent(DayOfWeek.of(rs.getInt(1)), k -> new ArrayList<>())
                        .add(new LocalTime[]{rs.getTime(2).toLocalTime(), rs.getTime(3).toLocalTime()}); }, c.id());
        Timestamp a = Timestamp.from(du.atStartOfDay(c.zone()).toInstant());
        Timestamp b = Timestamp.from(fin.atStartOfDay(c.zone()).toInstant());
        List<Instant[]> occupes = new ArrayList<>();
        plateforme.query("""
                SELECT debut, fin FROM rendez_vous WHERE cabinet_id = ? AND statut NOT IN ('ANNULE','ABSENT') AND debut < ? AND fin > ?
                UNION ALL
                SELECT debut, fin FROM reservation WHERE cabinet_id = ? AND statut = 'EN_ATTENTE' AND debut < ? AND fin > ?""",
                rs -> { occupes.add(new Instant[]{rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()}); },
                c.id(), b, a, c.id(), b, a);

        LocalDateTime auPlusTot = maintenant.plusHours(c.delai());
        for (LocalDate j = du; j.isBefore(fin); j = j.plusDays(1)) {
            List<Creneau> libres = new ArrayList<>();
            for (LocalTime[] p : plages.getOrDefault(j.getDayOfWeek(), List.of())) {
                // en minutes depuis minuit : une plage finit toujours le jour même
                int debutPlage = p[0].toSecondOfDay() / 60, finPlage = p[1].toSecondOfDay() / 60;
                for (int m = debutPlage; m + c.duree() <= finPlage; m += c.duree()) {
                    LocalDateTime d = j.atStartOfDay().plusMinutes(m), f = d.plusMinutes(c.duree());
                    if (d.isBefore(auPlusTot)) continue;
                    Instant di = d.atZone(c.zone()).toInstant(), fi = f.atZone(c.zone()).toInstant();
                    boolean pris = occupes.stream().anyMatch(o -> o[0].isBefore(fi) && o[1].isAfter(di));
                    if (!pris) libres.add(new Creneau(d, f));
                }
            }
            r.put(j, libres);
        }
        return r;
    }

    /** Demande sur un créneau encore libre ; au plus 3 demandes en attente par téléphone et par cabinet. */
    public Reponse demander(Cabinet c, LocalDateTime debut, Demande d, String ip) {
        boolean libre = creneaux(c, debut.toLocalDate(), 1).getOrDefault(debut.toLocalDate(), List.of()).stream()
                .anyMatch(x -> x.debut().equals(debut));
        if (!libre) return new Reponse(Resultat.CRENEAU_PRIS, null);
        String chiffres = d.telephone().replaceAll("\\D", "");
        Long enAttente = plateforme.queryForObject("""
                SELECT count(*) FROM reservation WHERE cabinet_id = ? AND statut = 'EN_ATTENTE' AND regexp_replace(telephone, '\\D', '', 'g') = ?""",
                Long.class, c.id(), chiffres);
        if (enAttente != null && enAttente >= 3) return new Reponse(Resultat.TROP_DE_DEMANDES, null);
        byte[] b = new byte[24];
        HASARD.nextBytes(b);
        String jeton = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        try {
            plateforme.update("""
                    INSERT INTO reservation (cabinet_id, debut, fin, nom, prenom, telephone, email, motif, langue, jeton, ip)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    c.id(), Timestamp.from(debut.atZone(c.zone()).toInstant()),
                    Timestamp.from(debut.plusMinutes(c.duree()).atZone(c.zone()).toInstant()),
                    d.nom(), d.prenom(), d.telephone(), d.email(), d.motif(), d.langue(), empreinte(jeton), ip);
        } catch (DuplicateKeyException e) {
            return new Reponse(Resultat.CRENEAU_PRIS, null);   // pris à l'instant par un autre patient
        }
        return new Reponse(Resultat.ENREGISTREE, jeton);
    }

    /** Demande ou rendez-vous accepté encore annulable par le patient (lien reçu). */
    public Optional<Annulable> annulable(String jeton) {
        if (jeton == null || jeton.length() > 100) return Optional.empty();
        return plateforme.query("""
                SELECT r.id, c.nom, r.debut, r.statut, c.fuseau FROM reservation r JOIN cabinet c ON c.id = r.cabinet_id
                WHERE r.jeton = ? AND r.statut IN ('EN_ATTENTE','ACCEPTEE') AND r.debut > now()""",
                (rs, i) -> new Annulable(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getTimestamp(3).toInstant().atZone(ZoneId.of(rs.getString(5))).toLocalDateTime(), rs.getString(4)),
                empreinte(jeton)).stream().findFirst();
    }

    /** Annulation par le patient ; un rendez-vous déjà posé passe « Annulé » (inscrit au journal du rendez-vous). */
    public boolean annuler(String jeton) {
        Optional<Annulable> a = annulable(jeton);
        if (a.isEmpty()) return false;
        List<UUID> rdv = plateforme.queryForList("""
                UPDATE reservation SET statut = 'ANNULEE', traite_le = now() WHERE id = ? AND statut IN ('EN_ATTENTE','ACCEPTEE')
                RETURNING rdv_id""", UUID.class, a.get().id());
        if (rdv.isEmpty()) return false;
        if (rdv.get(0) != null) {
            String ancien = plateforme.query("""
                    UPDATE rendez_vous r SET statut = 'ANNULE' FROM (SELECT statut FROM rendez_vous WHERE id = ?) avant
                    WHERE r.id = ? AND r.statut IN ('PLANIFIE','CONFIRME','REPORTE') RETURNING avant.statut""",
                    (rs, i) -> rs.getString(1), rdv.get(0), rdv.get(0)).stream().findFirst().orElse(null);
            if (ancien != null) {
                plateforme.update("""
                        INSERT INTO rdv_journal (cabinet_id, rdv_id, ancien, nouveau, utilisateur, detail)
                        SELECT cabinet_id, id, ?, 'ANNULE', 'Patient (en ligne)', 'Annulé par le patient depuis son lien' FROM rendez_vous WHERE id = ?""",
                        ancien, rdv.get(0));
            }
        }
        return true;
    }

    static String empreinte(String jeton) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jeton.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
