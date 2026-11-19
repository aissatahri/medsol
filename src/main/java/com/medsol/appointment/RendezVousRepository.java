package com.medsol.appointment;

import com.medsol.cabinet.CabinetService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rendez-vous et leur journal (repris de RendezVousRepository de SGCM-Cardio) : mêmes statuts et mêmes transitions
 * que le bureau. Les heures sont enregistrées en instants et présentées dans le fuseau du cabinet.
 */
@Repository
public class RendezVousRepository {

    public record Rdv(UUID id, LocalDate date, LocalTime debut, LocalTime fin, String titre, String statut,
                      UUID patientId, String nom, String prenom, String telephone, String sexe) {

        public String nomComplet() {
            String n = ((nom == null ? "" : nom.toUpperCase()) + " " + (prenom == null ? "" : prenom)).trim();
            return n.isEmpty() ? "Patient non renseigné" : n;
        }

        public String statutLibelle() { return libelle(statut); }

        /** Statuts accessibles depuis celui-ci (StatutRendezVous.suivants du bureau). */
        public List<String> suivants() {
            return switch (statut == null ? "PLANIFIE" : statut) {
                case "PLANIFIE", "REPORTE" -> List.of("CONFIRME", "EN_COURS", "REPORTE", "ANNULE", "ABSENT");
                case "CONFIRME" -> List.of("EN_COURS", "REPORTE", "ANNULE", "ABSENT");
                case "EN_COURS" -> List.of("TERMINE");
                case "ANNULE", "ABSENT" -> List.of("REPORTE");
                default -> List.of();
            };
        }

        public boolean peut(String nouveau) { return suivants().contains(nouveau); }

        public boolean actif() { return !"ANNULE".equals(statut) && !"ABSENT".equals(statut); }

        Rdv avecStatut(String s) { return new Rdv(id, date, debut, fin, titre, s, patientId, nom, prenom, telephone, sexe); }
    }

    public static String libelle(String statut) {
        return switch (statut == null ? "PLANIFIE" : statut) {
            case "PLANIFIE" -> "Planifié";
            case "CONFIRME" -> "Confirmé";
            case "EN_COURS" -> "En cours";
            case "TERMINE" -> "Terminé";
            case "REPORTE" -> "Reporté";
            case "ANNULE" -> "Annulé";
            case "ABSENT" -> "Absent";
            default -> statut;
        };
    }

    /** Ligne du journal d'un rendez-vous. */
    public record Audit(Instant quand, String ancien, String nouveau, String utilisateur, String detail) {
        public String transition() { return (ancien == null ? "" : libelle(ancien) + " → ") + libelle(nouveau); }
    }

    /** Plage de consultation du cabinet (Paramètres › Horaires). */
    public record Plage(DayOfWeek jour, LocalTime debut, LocalTime fin) {}

    private static final String SELECTION = """
            SELECT r.id, r.debut, r.fin, r.motif, r.statut, r.patient_id, p.nom, p.prenom, p.telephone, p.sexe
            FROM rendez_vous r JOIN patient p ON p.id = r.patient_id
            """;

    private final JdbcTemplate jdbc;
    private final CabinetService cabinets;
    private final TransactionTemplate tx;

    public RendezVousRepository(JdbcTemplate jdbc, CabinetService cabinets, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.cabinets = cabinets;
        this.tx = new TransactionTemplate(tm);
    }

    private ZoneId zone() { return cabinets.current().zone(); }

    private RowMapper<Rdv> mapper(ZoneId z) {
        return (rs, i) -> {
            ZonedDateTime d = rs.getTimestamp(2).toInstant().atZone(z);
            ZonedDateTime f = rs.getTimestamp(3).toInstant().atZone(z);
            return new Rdv(rs.getObject(1, UUID.class), d.toLocalDate(), d.toLocalTime(), f.toLocalTime(), rs.getString(4),
                    rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10));
        };
    }

    private Timestamp instant(LocalDate d, LocalTime t, ZoneId z) { return Timestamp.from(d.atTime(t).atZone(z).toInstant()); }

    /** Rendez-vous des jours [debut, fin] inclus. */
    public List<Rdv> entre(LocalDate debut, LocalDate fin) {
        ZoneId z = zone();
        return jdbc.query(SELECTION + "WHERE r.debut >= ? AND r.debut < ? ORDER BY r.debut", mapper(z),
                instant(debut, LocalTime.MIDNIGHT, z), instant(fin.plusDays(1), LocalTime.MIDNIGHT, z));
    }

    public Optional<Rdv> trouver(UUID id) {
        return jdbc.query(SELECTION + "WHERE r.id = ?", mapper(zone()), id).stream().findFirst();
    }

    public List<Audit> journal(UUID rdvId) {
        return jdbc.query("SELECT quand, ancien, nouveau, utilisateur, detail FROM rdv_journal WHERE rdv_id = ? ORDER BY quand DESC, id DESC LIMIT 50",
                (rs, i) -> new Audit(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), rdvId);
    }

    /** Rendez-vous actifs qui chevauchent ce créneau (hors celui qu'on déplace). */
    public List<Rdv> chevauchements(LocalDate date, LocalTime debut, LocalTime fin, UUID saufId) {
        ZoneId z = zone();
        return jdbc.query(SELECTION + """
                WHERE r.debut < ? AND r.fin > ? AND r.statut NOT IN ('ANNULE', 'ABSENT')
                  AND (?::uuid IS NULL OR r.id <> ?::uuid) ORDER BY r.debut""",
                mapper(z), instant(date, fin, z), instant(date, debut, z), saufId, saufId);
    }

    public UUID creer(UUID patientId, UUID creePar, LocalDate date, LocalTime debut, LocalTime fin, String titre, String utilisateur) {
        ZoneId z = zone();
        return tx.execute(s -> {
            UUID id = jdbc.queryForObject("""
                    INSERT INTO rendez_vous (cabinet_id, patient_id, debut, fin, statut, motif, cree_par)
                    VALUES (current_cabinet(), ?, ?, ?, 'PLANIFIE', ?, ?) RETURNING id""", UUID.class,
                    patientId, instant(date, debut, z), instant(date, fin, z), titre == null || titre.isBlank() ? null : titre.strip(), creePar);
            jdbc.update("INSERT INTO rdv_journal (cabinet_id, rdv_id, ancien, nouveau, utilisateur) VALUES (current_cabinet(), ?, NULL, 'PLANIFIE', ?)",
                    id, utilisateur);
            return id;
        });
    }

    /**
     * Change le statut seulement s'il est encore celui affiché, et l'inscrit au journal (même transaction) ;
     * un report déplace aussi le rendez-vous. Faux si un autre poste l'a modifié entre-temps.
     */
    public boolean changer(Rdv r, String nouveau, LocalDate date, LocalTime debut, LocalTime fin, String utilisateur, String detail) {
        ZoneId z = zone();
        return Boolean.TRUE.equals(tx.execute(t -> {
            int n = date == null
                    ? jdbc.update("UPDATE rendez_vous SET statut = ? WHERE id = ? AND statut = ?", nouveau, r.id(), r.statut())
                    : jdbc.update("UPDATE rendez_vous SET statut = ?, debut = ?, fin = ? WHERE id = ? AND statut = ?",
                    nouveau, instant(date, debut, z), instant(date, fin, z), r.id(), r.statut());
            if (n == 0) return false;
            jdbc.update("INSERT INTO rdv_journal (cabinet_id, rdv_id, ancien, nouveau, utilisateur, detail) VALUES (current_cabinet(), ?, ?, ?, ?, ?)",
                    r.id(), r.statut(), nouveau, utilisateur, detail == null || detail.isBlank() ? null : detail.strip());
            return true;
        }));
    }

    // ── Horaires du cabinet ─────────────────────────────────────────────

    public List<Plage> plages() {
        return jdbc.query("SELECT jour, debut, fin FROM disponibilite ORDER BY jour, debut",
                (rs, i) -> new Plage(DayOfWeek.of(rs.getInt(1)), rs.getTime(2).toLocalTime(), rs.getTime(3).toLocalTime()));
    }

    public void remplacerPlages(List<Plage> plages) {
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM disponibilite");
            for (Plage p : plages) {
                jdbc.update("INSERT INTO disponibilite (cabinet_id, jour, debut, fin) VALUES (current_cabinet(), ?, ?, ?)",
                        p.jour().getValue(), Time.valueOf(p.debut()), Time.valueOf(p.fin()));
            }
        });
    }
}
