package com.medsol.dossier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Allergies et antécédents du patient (repris de SGCM-Cardio). */
@Repository
public class AllergiesRepository {

    public record Allergie(UUID id, String nom, LocalDate date) {}

    public record Antecedent(UUID id, String type, String description, LocalDate date, String note) {}

    public static final List<String> TYPES_ANTECEDENT = List.of("MEDICAL", "CHIRURGICAL", "FAMILIAL", "SOCIAL", "AUTRE");

    /** Mots trop généraux pour signaler une allergie (« allergie à la pénicilline » ne doit pas toucher tout « allergie »). */
    private static final Set<String> MOTS_VIDES = Set.of("allergie", "allergique", "intolerance", "reaction", "contre",
            "indication", "medicament", "medicaments", "comprime", "allergy", "allergic");

    private final JdbcTemplate jdbc;

    public AllergiesRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Allergie> liste(UUID patientId) {
        return jdbc.query("SELECT id, nom, date_detection FROM allergie WHERE patient_id = ? ORDER BY cree_le",
                (rs, i) -> new Allergie(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, LocalDate.class)), patientId);
    }

    public void ajouter(UUID patientId, String nom, LocalDate date) {
        jdbc.update("INSERT INTO allergie (cabinet_id, patient_id, nom, date_detection) VALUES (current_cabinet(), ?, ?, ?)",
                patientId, nom.strip(), date == null ? null : Date.valueOf(date));
    }

    public boolean supprimer(UUID patientId, UUID id) {
        return jdbc.update("DELETE FROM allergie WHERE id = ? AND patient_id = ?", id, patientId) == 1;
    }

    public List<Antecedent> antecedents(UUID patientId) {
        return jdbc.query("SELECT id, type, description, date_debut, note FROM antecedent WHERE patient_id = ? "
                        + "ORDER BY date_debut DESC NULLS LAST, cree_le DESC",
                (rs, i) -> new Antecedent(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getString(5)), patientId);
    }

    public void ajouterAntecedent(UUID patientId, String type, String description, LocalDate date, String note) {
        jdbc.update("""
                INSERT INTO antecedent (cabinet_id, patient_id, type, description, date_debut, note)
                VALUES (current_cabinet(), ?, ?, ?, ?, ?)""",
                patientId, TYPES_ANTECEDENT.contains(type) ? type : "AUTRE", description.strip(),
                date == null ? null : Date.valueOf(date), note == null || note.isBlank() ? null : note.strip());
    }

    public boolean supprimerAntecedent(UUID patientId, UUID id) {
        return jdbc.update("DELETE FROM antecedent WHERE id = ? AND patient_id = ?", id, patientId) == 1;
    }

    /**
     * Allergies du patient qui concernent ce médicament : le premier mot du nom commercial cité dans l'allergie,
     * ou un mot de l'allergie (5 lettres et plus) présent dans le nom ou la DCI. Avertissement, jamais blocage.
     */
    public static List<String> concernees(List<Allergie> allergies, String nomMedicament, String dci) {
        String med = normaliser(nomMedicament + " " + (dci == null ? "" : dci));
        String premierMot = normaliser(nomMedicament).split(" ")[0];
        List<String> l = new ArrayList<>();
        for (Allergie a : allergies) {
            String texte = normaliser(a.nom());
            boolean touche = premierMot.length() >= 4 && (" " + texte + " ").contains(" " + premierMot + " ");
            for (String mot : texte.split(" ")) {
                if (mot.length() >= 5 && !MOTS_VIDES.contains(mot) && med.contains(mot)) touche = true;
            }
            if (touche) l.add(a.nom());
        }
        return l;
    }

    static String normaliser(String s) {
        String t = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return t.replaceAll("[^a-z0-9]+", " ").strip();
    }
}
