package com.medsol.dossier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Ordonnance d'une consultation et catalogue des médicaments (repris d'OrdonnanceRepository de SGCM-Cardio). */
@Repository
public class OrdonnanceRepository {

    public record Ligne(UUID id, long medicamentId, String medicament, String dose, String duree, String remarque,
                        String forme, String dci) {
        /** Posologie, durée et remarque, comme le bureau les enchaîne : « 1 cp/j - 3 mois - au repas ». */
        public String details() {
            StringBuilder b = new StringBuilder();
            for (String s : new String[]{dose, duree, remarque}) {
                if (s != null && !s.isBlank()) b.append(b.isEmpty() ? "" : " - ").append(s.strip());
            }
            return b.toString();
        }
    }

    public record Medicament(long id, String nom, String dci, String forme, String presentation) {}

    private static final RowMapper<Ligne> LIGNE = (rs, i) -> new Ligne(rs.getObject(1, UUID.class), rs.getLong(2),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8));

    private static final String SELECTION = "SELECT p.id, p.medicament_id, m.nom, p.dose, p.duree, p.remarque, m.forme, m.dci "
            + "FROM prescription p LEFT JOIN medicament m ON m.id = p.medicament_id ";

    private static final RowMapper<Medicament> MEDICAMENT = (rs, i) -> new Medicament(rs.getLong(1), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(5));

    private final JdbcTemplate jdbc;

    public OrdonnanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Ligne> lignes(UUID consultationId) {
        return jdbc.query(SELECTION + "WHERE p.consultation_id = ? ORDER BY p.ordre", LIGNE, consultationId);
    }

    public Optional<Ligne> ligne(UUID consultationId, UUID ligneId) {
        return jdbc.query(SELECTION + "WHERE p.consultation_id = ? AND p.id = ?", LIGNE, consultationId, ligneId).stream().findFirst();
    }

    /** Une seule instruction : rien n'est inséré si la consultation n'existe pas (ou est dans la corbeille). */
    public boolean ajouter(UUID consultationId, long medicamentId, String dose, String duree, String remarque) {
        return jdbc.update("""
                INSERT INTO prescription (cabinet_id, consultation_id, medicament_id, dose, duree, remarque)
                SELECT current_cabinet(), c.id, m.id, ?, ?, ? FROM consultation c JOIN medicament m ON m.id = ?
                WHERE c.id = ? AND c.supprimee_le IS NULL""",
                vide(dose), vide(duree), vide(remarque), medicamentId, consultationId) == 1;
    }

    public boolean modifier(UUID consultationId, UUID ligneId, String dose, String duree, String remarque) {
        return jdbc.update("UPDATE prescription SET dose = ?, duree = ?, remarque = ? WHERE id = ? AND consultation_id = ?",
                vide(dose), vide(duree), vide(remarque), ligneId, consultationId) == 1;
    }

    public boolean supprimer(UUID consultationId, UUID ligneId) {
        return jdbc.update("DELETE FROM prescription WHERE id = ? AND consultation_id = ?", ligneId, consultationId) == 1;
    }

    /** Consultation précédente du même patient ayant une ordonnance (pour la reprendre). */
    public Optional<UUID> precedenteAvecOrdonnance(UUID consultationId) {
        return jdbc.queryForList("""
                SELECT c.id FROM consultation c JOIN consultation courante ON courante.id = ?
                WHERE c.patient_id = courante.patient_id AND c.id <> courante.id AND c.supprimee_le IS NULL
                  AND c.date_consult < courante.date_consult
                  AND EXISTS (SELECT 1 FROM prescription p WHERE p.consultation_id = c.id)
                ORDER BY c.date_consult DESC LIMIT 1""", UUID.class, consultationId).stream().findFirst();
    }

    /** Copie l'ordonnance d'une autre consultation ; rien si l'ordonnance n'est pas vide. */
    public int reprendre(UUID depuis, UUID vers) {
        return jdbc.update("""
                INSERT INTO prescription (cabinet_id, consultation_id, medicament_id, dose, duree, remarque)
                SELECT current_cabinet(), ?, p.medicament_id, p.dose, p.duree, p.remarque FROM prescription p
                WHERE p.consultation_id = ? AND NOT EXISTS (SELECT 1 FROM prescription x WHERE x.consultation_id = ?)
                ORDER BY p.ordre""", vers, depuis, vers);
    }

    /**
     * Recherche dans le catalogue du pays du cabinet et ses ajouts : chaque mot tapé doit se trouver dans le nom,
     * la DCI ou la forme (« amlo 5 » trouve AMLOR 5 mg) ; ceux dont le nom commence par le texte d'abord, puis par dosage.
     */
    public List<Medicament> rechercher(String texte, int limite) {
        String q = texte == null ? "" : texte.strip();
        if (q.length() < 2) return List.of();
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("(m.cabinet_id IS NOT NULL OR m.pays = (SELECT pays FROM cabinet))");
        for (String mot : q.toLowerCase().split("\\s+")) {
            where.append(" AND lower(m.nom || ' ' || coalesce(m.dci, '') || ' ' || coalesce(m.forme, '')) LIKE ?");
            params.add("%" + echapper(mot) + "%");
        }
        params.add(echapper(q.toLowerCase()) + "%");
        params.add(limite);
        return jdbc.query("SELECT m.id, m.nom, m.dci, m.forme, m.presentation FROM medicament m WHERE " + where
                + " ORDER BY lower(m.nom) LIKE ? DESC, m.nom,"
                + " nullif(replace(substring(m.forme from '[0-9]+(?:[.,][0-9]+)?'), ',', '.'), '')::numeric NULLS LAST, m.forme"
                + " LIMIT ?", MEDICAMENT, params.toArray());
    }

    public Optional<Medicament> medicament(long id) {
        return jdbc.query("SELECT id, nom, dci, forme, presentation FROM medicament WHERE id = ?", MEDICAMENT, id)
                .stream().findFirst();
    }

    /** Médicament absent du catalogue, ajouté au catalogue du cabinet ; repris s'il existe déjà (pas de doublon). */
    public long trouverOuCreer(String nom, String forme, String dci) {
        String n = vide(nom);
        String f = vide(forme);
        List<Long> existant = jdbc.queryForList("""
                SELECT id FROM medicament WHERE lower(nom) = lower(?) AND lower(coalesce(forme, '')) = lower(?)
                  AND (cabinet_id IS NOT NULL OR pays = (SELECT pays FROM cabinet)) ORDER BY cabinet_id NULLS LAST, id LIMIT 1""",
                Long.class, n, f);
        if (!existant.isEmpty()) return existant.get(0);
        return jdbc.queryForObject("INSERT INTO medicament (cabinet_id, nom, forme, dci) VALUES (current_cabinet(), ?, ?, ?) RETURNING id",
                Long.class, n, f.isEmpty() ? null : f, vide(dci).isEmpty() ? null : vide(dci));
    }

    static String echapper(String s) { return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }

    static String vide(String s) { return s == null ? "" : s.strip(); }
}
