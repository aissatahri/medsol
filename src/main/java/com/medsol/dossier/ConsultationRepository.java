package com.medsol.dossier;

import com.medsol.dossier.Catalogues.TypeChamp;
import com.medsol.dossier.Catalogues.TypeConstante;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * Consultations du dossier médical (repris de SGCM-Cardio) : une par patient et par jour, champs cliniques et
 * constantes paramétrés, IMC calculé, suppression vers la corbeille dès qu'elle a du contenu.
 */
@Repository
public class ConsultationRepository {

    public record Consultation(UUID id, UUID patientId, LocalDate date) {}

    /** Ligne de la liste des consultations du dossier. */
    public record Visite(UUID id, LocalDate date, String motif) {}

    /** Valeurs saisies : c_{type} (champ clinique), s_{type} (constante simple), x_{type}_{clé} (complexe). */
    public record Saisie(Map<Long, String> champs, Map<Long, String> simples, Map<Long, Map<String, String>> complexes) {
        public static Saisie vide() { return new Saisie(new HashMap<>(), new HashMap<>(), new HashMap<>()); }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ConsultationRepository(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
    }

    /** Consultation du patient à cette date, créée au besoin (une seule par jour). */
    public UUID duJour(UUID patientId, UUID medecinId, LocalDate jour) {
        jdbc.update("""
                INSERT INTO consultation (cabinet_id, patient_id, medecin_id, date_consult)
                VALUES (current_cabinet(), ?, ?, ?)
                ON CONFLICT (cabinet_id, patient_id, date_consult) WHERE supprimee_le IS NULL DO NOTHING""",
                patientId, medecinId, Date.valueOf(jour));
        return jdbc.queryForObject("SELECT id FROM consultation WHERE patient_id = ? AND date_consult = ? AND supprimee_le IS NULL",
                UUID.class, patientId, Date.valueOf(jour));
    }

    public Optional<Consultation> trouver(UUID id) {
        return jdbc.query("SELECT id, patient_id, date_consult FROM consultation WHERE id = ? AND supprimee_le IS NULL",
                (rs, i) -> new Consultation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, LocalDate.class)), id).stream().findFirst();
    }

    /** Consultations du patient, de la plus récente à la plus ancienne. */
    public List<Visite> visites(UUID patientId) {
        return jdbc.query("SELECT id, date_consult, motif FROM consultation WHERE patient_id = ? AND supprimee_le IS NULL "
                        + "ORDER BY date_consult DESC",
                (rs, i) -> new Visite(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), rs.getString(3)), patientId);
    }

    public Saisie lire(UUID id) {
        Saisie s = Saisie.vide();
        jdbc.query("SELECT type_id, valeur FROM consultation_champ WHERE consultation_id = ?",
                rs -> { s.champs().put(rs.getLong(1), rs.getString(2)); }, id);
        jdbc.query("SELECT type_id, cle, valeur FROM consultation_constante WHERE consultation_id = ?", rs -> {
            if (rs.getString(2).isEmpty()) s.simples().put(rs.getLong(1), rs.getString(3));
            else s.complexes().computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>()).put(rs.getString(2), rs.getString(3));
        }, id);
        return s;
    }

    /**
     * Enregistre champs et constantes comme le bureau : champ vidé supprimé, nombre normalisé (virgule acceptée),
     * IMC recalculé depuis le poids et la taille. Le motif affiché dans la liste suit le champ « Symptômes ».
     *
     * @return les valeurs telles qu'elles sont désormais en base
     */
    public Saisie enregistrer(UUID id, List<TypeChamp> typesChamps, List<TypeConstante> typesConstantes, Saisie saisie) {
        Saisie r = Saisie.vide();
        for (TypeChamp t : typesChamps) {
            String v = propre(saisie.champs().get(t.id()));
            if (v != null) r.champs().put(t.id(), v);
        }
        for (TypeConstante t : typesConstantes) {
            if (t.calcule()) continue;
            if (!t.complexe()) {
                String v = propre(saisie.simples().get(t.id()));
                if (v != null) r.simples().put(t.id(), nombreOuTexte(v));
            } else {
                Map<String, String> sous = new LinkedHashMap<>();
                Map<String, String> voulues = saisie.complexes().getOrDefault(t.id(), Map.of());
                for (Catalogues.Cle k : t.cles()) {
                    String v = propre(voulues.get(k.cle()));
                    if (v != null) sous.put(k.cle(), nombreOuTexte(v));
                }
                if (!sous.isEmpty()) r.complexes().put(t.id(), sous);
            }
        }
        Double poids = valeur(typesConstantes, r.simples(), "POIDS");
        Double taille = valeur(typesConstantes, r.simples(), "TAILLE");
        typesConstantes.stream().filter(TypeConstante::calcule).findFirst().ifPresent(imc -> {
            if (poids != null && taille != null && poids > 0 && taille > 0) {
                double t = taille > 3 ? taille / 100 : taille;   // taille saisie en cm
                r.simples().put(imc.id(), nombreTexte(Math.round(poids / (t * t) * 100) / 100.0));
            }
        });
        String symptomes = typesChamps.stream().filter(t -> "SYMPTOMES".equals(t.nom())).findFirst()
                .map(t -> r.champs().get(t.id())).orElse(null);

        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM consultation_champ WHERE consultation_id = ?", id);
            jdbc.update("DELETE FROM consultation_constante WHERE consultation_id = ?", id);
            r.champs().forEach((type, v) -> jdbc.update(
                    "INSERT INTO consultation_champ (consultation_id, cabinet_id, type_id, valeur) VALUES (?, current_cabinet(), ?, ?)",
                    id, type, v));
            r.simples().forEach((type, v) -> jdbc.update(
                    "INSERT INTO consultation_constante (consultation_id, cabinet_id, type_id, cle, valeur) VALUES (?, current_cabinet(), ?, '', ?)",
                    id, type, v));
            r.complexes().forEach((type, sous) -> sous.forEach((cle, v) -> jdbc.update(
                    "INSERT INTO consultation_constante (consultation_id, cabinet_id, type_id, cle, valeur) VALUES (?, current_cabinet(), ?, ?, ?)",
                    id, type, cle, v)));
            jdbc.update("UPDATE consultation SET motif = ?, modifie_le = now() WHERE id = ?",
                    symptomes == null ? null : symptomes.length() > 200 ? symptomes.substring(0, 200) : symptomes, id);
        });
        return r;
    }

    /** Change la date (une seule consultation par jour et par patient). @return false si ce jour est déjà pris. */
    public boolean changerDate(UUID id, LocalDate jour) {
        Integer autres = jdbc.queryForObject("""
                SELECT count(*) FROM consultation c JOIN consultation x ON x.id = ?
                WHERE c.patient_id = x.patient_id AND c.date_consult = ? AND c.id <> x.id AND c.supprimee_le IS NULL""",
                Integer.class, id, Date.valueOf(jour));
        if (autres != null && autres > 0) return false;
        return jdbc.update("UPDATE consultation SET date_consult = ?, modifie_le = now() WHERE id = ?", Date.valueOf(jour), id) == 1;
    }

    /** Vide : supprimée ; avec du contenu : rangée dans la corbeille (rétablissable). @return true si mise en corbeille. */
    public boolean supprimer(UUID id, String motif, UUID par) {
        Boolean contenu = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM consultation_champ WHERE consultation_id = ?)
                    OR EXISTS (SELECT 1 FROM consultation_constante WHERE consultation_id = ?)
                    OR EXISTS (SELECT 1 FROM prescription WHERE consultation_id = ?)
                    OR EXISTS (SELECT 1 FROM examen WHERE consultation_id = ?)
                    OR EXISTS (SELECT 1 FROM consultation_acte WHERE consultation_id = ?)
                    OR EXISTS (SELECT 1 FROM versement WHERE consultation_id = ?)
                    OR EXISTS (SELECT 1 FROM fichier WHERE consultation_id = ?)
                    OR EXISTS (SELECT 1 FROM redaction WHERE consultation_id = ?)""", Boolean.class, id, id, id, id, id, id, id, id);
        if (Boolean.TRUE.equals(contenu)) {
            jdbc.update("UPDATE consultation SET supprimee_le = now(), motif_suppression = ?, supprimee_par = ? WHERE id = ?",
                    motif == null || motif.isBlank() ? null : motif.strip(), par, id);
            return true;
        }
        jdbc.update("DELETE FROM consultation WHERE id = ?", id);
        return false;
    }

    /** Consultation de la corbeille, avec un résumé de son contenu. */
    public record Supprimee(UUID id, LocalDate date, UUID patientId, String patient, long dossier, String resume,
                            String motif, java.time.Instant le, String par) {}

    public List<Supprimee> corbeille() {
        return jdbc.query("""
                SELECT c.id, c.date_consult, p.id, upper(p.nom) || ' ' || p.prenom, p.numero,
                       concat_ws(' · ',
                         nullif((SELECT count(*) FROM prescription x WHERE x.consultation_id = c.id), 0) || ' médicament(s)',
                         nullif((SELECT count(*) FROM examen x WHERE x.consultation_id = c.id), 0) || ' examen(s)',
                         nullif((SELECT count(*) FROM consultation_acte x WHERE x.consultation_id = c.id), 0) || ' acte(s)',
                         nullif((SELECT count(*) FROM versement x WHERE x.consultation_id = c.id AND x.annule_le IS NULL), 0) || ' versement(s)'),
                       c.motif_suppression, c.supprimee_le, u.nom_complet
                FROM consultation c JOIN patient p ON p.id = c.patient_id LEFT JOIN utilisateur u ON u.id = c.supprimee_par
                WHERE c.supprimee_le IS NOT NULL ORDER BY c.supprimee_le DESC LIMIT 300""",
                (rs, i) -> new Supprimee(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant(), rs.getString(9)));
    }

    /** Rétablit à l'identique ; refusé si le patient a entre-temps une autre consultation ce jour-là. */
    public boolean retablir(UUID id) {
        try {
            return jdbc.update("UPDATE consultation SET supprimee_le = NULL, motif_suppression = NULL, supprimee_par = NULL "
                    + "WHERE id = ? AND supprimee_le IS NOT NULL", id) == 1;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    // ── utilitaires (comme le bureau) ──────────────────────────────────────

    private static String propre(String v) { return v == null || v.isBlank() ? null : v.strip(); }

    static Double nombre(String v) {
        if (v == null) return null;
        try { return Double.parseDouble(v.strip().replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    static String nombreTexte(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String nombreOuTexte(String v) {
        Double d = nombre(v);
        return d != null ? nombreTexte(d) : v;
    }

    private static Double valeur(List<TypeConstante> types, Map<Long, String> simples, String nom) {
        return types.stream().filter(t -> nom.equals(t.nom())).findFirst().map(t -> nombre(simples.get(t.id()))).orElse(null);
    }
}
