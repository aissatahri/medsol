package com.medsol.dossier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Analyses et imagerie demandées lors d'une consultation, avec leur catalogue de types (repris d'ExamenRepository
 * de SGCM-Cardio). Noms et codes dans la langue de l'utilisateur, le français à défaut.
 */
@Repository
public class ExamenRepository {

    public enum Genre {
        ANALYSE, IMAGERIE;

        /** Dans les adresses : /examens/analyse, /examens/imagerie. */
        public String chemin() { return name().toLowerCase(Locale.ROOT); }

        public static Genre depuis(String chemin) { return valueOf(chemin.toUpperCase(Locale.ROOT)); }
    }

    public record Examen(UUID id, Long typeId, String nom, String code, String description, LocalDate date, String resultat) {
        public String intitule() {
            if (nom != null && !nom.isBlank()) return nom;
            return code != null && !code.isBlank() ? code : "?";
        }

        public boolean aResultat() { return resultat != null && !resultat.isBlank(); }
    }

    public record Type(long id, String nom, String code) {}

    private final JdbcTemplate jdbc;

    public ExamenRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static String l() { return Catalogues.langue(); }

    private static final String SELECTION = """
            SELECT e.id, e.type_id, coalesce(t.nom->>?, t.nom->>'fr'), coalesce(t.code->>?, t.code->>'fr'),
                   e.description, e.date_examen, e.resultat
            FROM examen e LEFT JOIN type_examen t ON t.id = e.type_id
            """;

    private static final RowMapper<Examen> EXAMEN = (rs, i) -> {
        long type = rs.getLong(2);
        return new Examen(rs.getObject(1, UUID.class), rs.wasNull() ? null : type, rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getObject(6, LocalDate.class), rs.getString(7));
    };

    public List<Examen> liste(Genre g, UUID consultationId) {
        return jdbc.query(SELECTION + "WHERE e.consultation_id = ? AND e.genre = ? ORDER BY e.ordre", EXAMEN,
                l(), l(), consultationId, g.name());
    }

    public Optional<Examen> trouver(Genre g, UUID consultationId, UUID examenId) {
        return jdbc.query(SELECTION + "WHERE e.consultation_id = ? AND e.genre = ? AND e.id = ?", EXAMEN,
                l(), l(), consultationId, g.name(), examenId).stream().findFirst();
    }

    /** Date de l'examen = date de la consultation ; rien n'est inséré si la consultation n'existe pas. */
    public boolean ajouter(Genre g, UUID consultationId, long typeId, String description) {
        return jdbc.update("""
                INSERT INTO examen (cabinet_id, consultation_id, genre, type_id, description, date_examen)
                SELECT current_cabinet(), c.id, ?, t.id, ?, c.date_consult FROM consultation c
                JOIN type_examen t ON t.id = ? AND t.genre = ?
                WHERE c.id = ? AND c.supprimee_le IS NULL""",
                g.name(), vide(description), typeId, g.name(), consultationId) == 1;
    }

    /** La date de l'examen n'est changée que si elle est fournie. */
    public boolean modifier(Genre g, UUID consultationId, UUID examenId, String description, String resultat, LocalDate date) {
        return jdbc.update("""
                UPDATE examen SET description = ?, resultat = ?, date_examen = coalesce(?, date_examen)
                WHERE id = ? AND consultation_id = ? AND genre = ?""",
                vide(description), resultat == null || resultat.isBlank() ? null : resultat.strip(),
                date == null ? null : Date.valueOf(date), examenId, consultationId, g.name()) == 1;
    }

    public boolean supprimer(Genre g, UUID consultationId, UUID examenId) {
        return jdbc.update("DELETE FROM examen WHERE id = ? AND consultation_id = ? AND genre = ?", examenId, consultationId, g.name()) == 1;
    }

    /** Catalogue : chaque mot tapé dans le nom ou le code ; ceux dont le nom commence par le texte d'abord. */
    public List<Type> types(Genre g, String texte, int limite) {
        String q = texte == null ? "" : texte.strip().toLowerCase();
        if (q.length() < 2) return List.of();
        String lang = l();
        List<Object> params = new ArrayList<>(List.of(lang, lang, g.name()));
        StringBuilder where = new StringBuilder("genre = ?");
        for (String mot : q.split("\\s+")) {
            where.append(" AND lower(coalesce(nom->>?, nom->>'fr') || ' ' || coalesce(code->>?, code->>'fr', '')) LIKE ?");
            params.add(lang);
            params.add(lang);
            params.add("%" + OrdonnanceRepository.echapper(mot) + "%");
        }
        params.add(lang);
        params.add(OrdonnanceRepository.echapper(q) + "%");
        params.add(limite);
        return jdbc.query("SELECT id, coalesce(nom->>?, nom->>'fr') AS n, coalesce(code->>?, code->>'fr') FROM type_examen WHERE "
                        + where + " ORDER BY lower(coalesce(nom->>?, nom->>'fr')) LIKE ? DESC, n LIMIT ?",
                (rs, i) -> new Type(rs.getLong(1), rs.getString(2), rs.getString(3)), params.toArray());
    }

    /** Type absent du catalogue, ajouté au catalogue du cabinet ; repris s'il existe déjà sous ce nom. */
    public long trouverOuCreerType(Genre g, String nom, String code) {
        String n = vide(nom);
        List<Long> existant = jdbc.queryForList("""
                SELECT id FROM type_examen WHERE genre = ? AND (lower(nom->>'fr') = lower(?) OR lower(nom->>'en') = lower(?))
                ORDER BY cabinet_id NULLS LAST, id LIMIT 1""", Long.class, g.name(), n, n);
        if (!existant.isEmpty()) return existant.get(0);
        String c = vide(code);
        return jdbc.queryForObject("""
                INSERT INTO type_examen (genre, cabinet_id, nom, code)
                VALUES (?, current_cabinet(), jsonb_build_object(?, ?), CASE WHEN ? = '' THEN '{}'::jsonb ELSE jsonb_build_object(?, ?) END)
                RETURNING id""", Long.class, g.name(), l(), n, c, l(), c);
    }

    private static String vide(String s) { return s == null ? "" : s.strip(); }
}
