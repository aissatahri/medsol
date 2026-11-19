package com.medsol.queue;

import com.medsol.cabinet.CabinetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/** Salle d'attente du jour (repris de SalleAttenteRepository de SGCM-Cardio). */
@Repository
public class QueueRepository {

    public static final Set<String> ETATS = Set.of("NORMAL", "URGENT", "AGE", "GRAVE", "AUTRE");
    public static final List<String> STATUTS = List.of("EN_ATTENTE", "EN_CONSULTATION", "TERMINE");

    private static final String SELECTION = """
            SELECT f.id, f.patient_id, upper(p.nom) || ' ' || p.prenom AS nom, f.numero, f.statut, f.motif, f.arrive_le,
                   f.debut_le, f.etat, p.sexe
            FROM file_attente f JOIN patient p ON p.id = f.patient_id
            """;

    private static final RowMapper<QueueEntry> MAPPER = (rs, i) -> new QueueEntry(rs.getObject("id", UUID.class),
            rs.getObject("patient_id", UUID.class), rs.getString("nom"), rs.getInt("numero"), rs.getString("statut"),
            rs.getString("motif"), rs.getTimestamp("arrive_le").toInstant(),
            rs.getTimestamp("debut_le") == null ? null : rs.getTimestamp("debut_le").toInstant(), rs.getString("etat"), rs.getString("sexe"));

    private final JdbcTemplate jdbc;
    private final CabinetService cabinets;
    private final TransactionTemplate tx;

    public QueueRepository(JdbcTemplate jdbc, CabinetService cabinets, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.cabinets = cabinets;
        this.tx = new TransactionTemplate(tm);
    }

    /** Passages du jour : en consultation, puis les prioritaires, puis par ordre d'arrivée. */
    public List<QueueEntry> today(LocalDate day) {
        return jdbc.query(SELECTION + """
                WHERE f.jour = ?
                ORDER BY CASE f.statut WHEN 'EN_CONSULTATION' THEN 0 WHEN 'EN_ATTENTE' THEN 1 ELSE 2 END,
                         CASE WHEN f.statut = 'EN_ATTENTE' AND f.etat <> 'NORMAL' THEN 0 ELSE 1 END, f.numero""",
                MAPPER, Date.valueOf(day));
    }

    public List<QueueEntry> today(LocalDate day, Collection<String> statuts) {
        return today(day).stream().filter(e -> statuts.contains(e.statut())).toList();
    }

    /** Patients dont un passage est ouvert aujourd'hui (pour la recherche d'ajout). */
    public Set<UUID> presents(LocalDate day) {
        return new HashSet<>(jdbc.queryForList("SELECT patient_id FROM file_attente WHERE jour = ? AND statut IN ('EN_ATTENTE','EN_CONSULTATION')",
                UUID.class, Date.valueOf(day)));
    }

    public Optional<QueueEntry> find(UUID id) {
        return jdbc.query(SELECTION + "WHERE f.id = ?", MAPPER, id).stream().findFirst();
    }

    public boolean add(UUID patientId, String motif, UUID rdvId, LocalDate day) {
        return add(patientId, motif, rdvId, day, "NORMAL");
    }

    /** Ajoute un patient (ticket du jour). @return false s'il y est déjà (en attente ou en consultation). */
    public boolean add(UUID patientId, String motif, UUID rdvId, LocalDate day, String etat) {
        String e = ETATS.contains(etat) ? etat : "NORMAL";
        try {
            return Boolean.TRUE.equals(tx.execute(s -> {
                long numero = cabinets.nextValue("ticket:" + day);
                jdbc.update("""
                        INSERT INTO file_attente (cabinet_id, patient_id, jour, numero, motif, rdv_id, etat)
                        VALUES (current_cabinet(), ?, ?, ?, ?, ?, ?)""",
                        patientId, Date.valueOf(day), (int) numero, blank(motif), rdvId, e);
                if (rdvId != null) jdbc.update("UPDATE rendez_vous SET statut = 'EN_COURS' WHERE id = ? AND statut IN ('PLANIFIE','CONFIRME','REPORTE')", rdvId);
                return true;
            }));
        } catch (DataIntegrityViolationException ex) {
            return false;   // index unique : déjà dans la salle d'attente
        }
    }

    public enum Changement { FAIT, OCCUPE, PERIME }

    /**
     * Change le statut si le passage est toujours dans l'état attendu (deux postes peuvent agir en même temps).
     * Un seul patient en consultation à la fois (un médecin par cabinet).
     */
    public Changement statut(UUID id, String attendu, String nouveau) {
        if (!STATUTS.contains(nouveau)) return Changement.PERIME;
        try {
            Integer n = tx.execute(s -> {
                int m = switch (nouveau) {
                    case "EN_CONSULTATION" -> jdbc.update("UPDATE file_attente SET statut = 'EN_CONSULTATION', debut_le = now() WHERE id = ? AND statut = ?", id, attendu);
                    case "TERMINE" -> jdbc.update("UPDATE file_attente SET statut = 'TERMINE', fin_le = now() WHERE id = ? AND statut = ?", id, attendu);
                    default -> jdbc.update("UPDATE file_attente SET statut = 'EN_ATTENTE', debut_le = NULL, fin_le = NULL WHERE id = ? AND statut = ?", id, attendu);
                };
                if (m == 1 && "TERMINE".equals(nouveau)) {
                    jdbc.update("UPDATE rendez_vous SET statut = 'TERMINE' WHERE id = (SELECT rdv_id FROM file_attente WHERE id = ?)", id);
                }
                return m;
            });
            return n != null && n == 1 ? Changement.FAIT : Changement.PERIME;
        } catch (DataIntegrityViolationException e) {
            return Changement.OCCUPE;   // un patient est déjà en consultation (ou remis en attente alors qu'il y est déjà)
        }
    }

    public boolean call(UUID id) { return statut(id, "EN_ATTENTE", "EN_CONSULTATION") == Changement.FAIT; }

    public boolean finish(UUID id) { return statut(id, "EN_CONSULTATION", "TERMINE") == Changement.FAIT; }

    /** Retire le passage (doublon, patient reparti) : gardé dans l'historique du jour comme « retiré ». */
    public boolean remove(UUID id) {
        return jdbc.update("UPDATE file_attente SET statut = 'ABSENT', fin_le = now() WHERE id = ? AND statut IN ('EN_ATTENTE','EN_CONSULTATION','TERMINE')", id) == 1;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
