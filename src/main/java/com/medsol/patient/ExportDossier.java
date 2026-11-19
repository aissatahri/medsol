package com.medsol.patient;

import com.medsol.cabinet.CabinetService;
import com.medsol.dossier.AllergiesRepository;
import com.medsol.dossier.ConsultationRepository;
import com.medsol.dossier.ExamenRepository;
import com.medsol.dossier.OrdonnanceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Dossier complet d'un patient, à lui remettre (droit d'accès et portabilité : RGPD art. 15 et 20, HIPAA « right of
 * access »). Archive ZIP :
 * <ul>
 *   <li>{@code dossier.html} : lecture et impression (identité, allergies, antécédents, consultations, ordonnances,
 *       examens) ;</li>
 *   <li>{@code dossier.json} : toutes les données, lisibles par une machine ;</li>
 *   <li>{@code fichiers/} : les fichiers joints d'origine.</li>
 * </ul>
 * Les tables sont découvertes dans le schéma (toute table qui porte {@code patient_id}, ou qui dépend d'une telle table,
 * comme les constantes d'une consultation) : une table ajoutée plus tard est exportée sans y penser. Connexions du
 * cabinet (RLS) : seules les données du cabinet sortent.
 */
@Service
public class ExportDossier {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> COLONNES_MASQUEES = Set.of("cabinet_id");

    private final JdbcTemplate jdbc;
    private final CabinetService cabinets;
    private final TemplateEngine moteur;
    private final AllergiesRepository allergies;
    private final ConsultationRepository consultations;
    private final OrdonnanceRepository ordonnances;
    private final ExamenRepository examens;

    public ExportDossier(JdbcTemplate jdbc, CabinetService cabinets, TemplateEngine moteur, AllergiesRepository allergies,
                         ConsultationRepository consultations, OrdonnanceRepository ordonnances, ExamenRepository examens) {
        this.jdbc = jdbc;
        this.cabinets = cabinets;
        this.moteur = moteur;
        this.allergies = allergies;
        this.consultations = consultations;
        this.ordonnances = ordonnances;
        this.examens = examens;
    }

    public record Champ(String libelle, String valeur) {}
    public record Constante(String libelle, String cle, String valeur, String unite) {}
    public record Visite(ConsultationRepository.Visite visite, List<Champ> champs, List<Constante> constantes,
                         List<OrdonnanceRepository.Ligne> prescriptions, List<ExamenRepository.Examen> analyses,
                         List<ExamenRepository.Examen> imageries) {}

    /** Patient du cabinet (RLS) : nom pour le fichier, ou vide. */
    public Optional<String> nom(UUID patient) {
        return jdbc.query("SELECT nom || ' ' || coalesce(prenom, '') FROM patient WHERE id = ?", (rs, i) -> rs.getString(1), patient)
                .stream().findFirst();
    }

    public void ecrire(UUID patient, String exportePar, Locale langue, OutputStream sortie) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(sortie, StandardCharsets.UTF_8);
        Map<String, Object> racine = new LinkedHashMap<>();
        racine.put("format", "medsol-dossier-patient");
        racine.put("version", 1);
        racine.put("exporte_le", Instant.now().toString());
        racine.put("exporte_par", exportePar);
        CabinetService.Cabinet cab = cabinets.current();
        racine.put("cabinet", Map.of("nom", cab.nom(), "pays", Objects.toString(cab.pays(), "")));

        List<Map<String, Object>> identite = lignes("patient", "SELECT * FROM patient WHERE id = ?", patient, zip);
        racine.put("patient", identite.isEmpty() ? Map.of() : identite.get(0));

        Map<String, List<Map<String, Object>>> donnees = new TreeMap<>();
        Map<String, List<String>> colonnes = colonnes();
        Set<String> avecPatient = new TreeSet<>();
        colonnes.forEach((t, c) -> { if (c.contains("patient_id") && !t.equals("patient")) avecPatient.add(t); });
        for (String t : avecPatient) {
            donnees.put(t, lignes(t, "SELECT * FROM " + q(t) + " WHERE patient_id = ?" + ordre(colonnes.get(t)), patient, zip));
        }
        // tables rattachées à une table du patient : constantes et ordonnances d'une consultation, rappels d'un rendez-vous...
        for (var e : colonnes.entrySet()) {
            String t = e.getKey();
            if (t.equals("patient") || avecPatient.contains(t)) continue;
            for (String parent : avecPatient) {
                String fk = parent + "_id";   // tables au singulier : consultation_id, rendez_vous_id...
                if (e.getValue().contains(fk) && colonnes.get(parent).contains("id")) {
                    donnees.put(t, lignes(t, "SELECT * FROM " + q(t) + " WHERE " + q(fk) + " IN (SELECT id FROM " + q(parent)
                            + " WHERE patient_id = ?)" + ordre(e.getValue()), patient, zip));
                    break;
                }
            }
        }
        donnees.values().removeIf(List::isEmpty);
        racine.put("donnees", donnees);

        zip.putNextEntry(new ZipEntry("dossier.json"));
        zip.write(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(racine));
        zip.closeEntry();

        zip.putNextEntry(new ZipEntry("dossier.html"));
        zip.write(lisible(patient, racine, donnees, langue).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();

        zip.putNextEntry(new ZipEntry("LISEZMOI.txt"));
        zip.write(("""
                Dossier médical exporté depuis MedSol (%s) le %s.

                dossier.html : à ouvrir dans un navigateur (lecture, impression).
                dossier.json : toutes les données, format lisible par une machine (portabilité).
                fichiers/    : fichiers joints d'origine (résultats, comptes rendus, images).

                Ce dossier contient des données de santé : conservez-le en lieu sûr.
                """).formatted(cab.nom(), racine.get("exporte_le")).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.finish();
    }

    /** Colonnes visibles du rôle des cabinets (les tables de la plateforme, sans droit, n'apparaissent pas). */
    private Map<String, List<String>> colonnes() {
        Map<String, List<String>> m = new TreeMap<>();
        jdbc.query("""
                SELECT c.table_name, c.column_name FROM information_schema.columns c
                JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE' AND t.table_name NOT LIKE 'flyway%'
                ORDER BY c.table_name, c.ordinal_position""",
                rs -> { m.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2)); });
        return m;
    }

    private static String ordre(List<String> colonnes) {
        for (String c : List.of("date_consult", "debut", "cree_le", "ordre")) if (colonnes.contains(c)) return " ORDER BY " + q(c);
        return "";
    }

    private static String q(String identifiant) { return "\"" + identifiant.replace("\"", "\"\"") + "\""; }

    /** Lignes d'une table ; les contenus binaires partent dans fichiers/ et la ligne garde leur chemin. */
    private List<Map<String, Object>> lignes(String table, String sql, UUID patient, ZipOutputStream zip) {
        List<Map<String, Object>> liste = new ArrayList<>();
        jdbc.query(sql, rs -> {
            ResultSetMetaData md = rs.getMetaData();
            Map<String, Object> l = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String col = md.getColumnLabel(i);
                if (!COLONNES_MASQUEES.contains(col)) l.put(col, valeur(rs, i, md));
            }
            for (int i = 1; i <= md.getColumnCount(); i++) {
                if ("bytea".equals(md.getColumnTypeName(i)) && rs.getBytes(i) != null) {
                    String nom = l.get("nom") instanceof String s ? s : md.getColumnLabel(i);
                    String chemin = "fichiers/" + table + "/" + (liste.size() + 1) + "-" + nom.replaceAll("[\\\\/:*?\"<>|]", "_");
                    try {
                        zip.putNextEntry(new ZipEntry(chemin));
                        zip.write(rs.getBytes(i));
                        zip.closeEntry();
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    l.put(md.getColumnLabel(i), chemin);
                }
            }
            liste.add(l);
        }, patient);
        return liste;
    }

    private static Object valeur(ResultSet rs, int i, ResultSetMetaData md) throws SQLException {
        String type = md.getColumnTypeName(i);
        if ("bytea".equals(type)) return null;   // remplacé par le chemin du fichier
        Object v = rs.getObject(i);
        if (v == null) return null;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant().toString();
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof java.sql.Array a) return Arrays.asList((Object[]) a.getArray());
        if ("jsonb".equals(type) || "json".equals(type)) {
            JsonNode n = JSON.readTree(v.toString());
            return n;
        }
        if (v instanceof Number || v instanceof Boolean || v instanceof String) return v;
        return v.toString();
    }

    private String lisible(UUID patient, Map<String, Object> racine, Map<String, List<Map<String, Object>>> donnees, Locale langue) {
        String l = langue.getLanguage();
        List<Visite> visites = new ArrayList<>();
        for (ConsultationRepository.Visite v : consultations.visites(patient)) {
            List<Champ> champs = jdbc.query("""
                    SELECT coalesce(t.libelle->>?, t.libelle->>'fr', t.nom), c.valeur FROM consultation_champ c
                    JOIN type_champ_clinique t ON t.id = c.type_id WHERE c.consultation_id = ? AND c.valeur <> '' ORDER BY t.ordre, t.id""",
                    (rs, i) -> new Champ(rs.getString(1), rs.getString(2)), l, v.id());
            List<Constante> constantes = jdbc.query("""
                    SELECT coalesce(t.libelle->>?, t.libelle->>'fr', t.nom), coalesce(k.libelle->>?, k.libelle->>'fr', c.cle), c.valeur,
                           coalesce(k.unite, t.unite)
                    FROM consultation_constante c JOIN type_constante t ON t.id = c.type_id
                    LEFT JOIN type_constante_cle k ON k.type_constante_id = t.id AND k.cle = c.cle
                    WHERE c.consultation_id = ? AND c.valeur <> '' ORDER BY t.ordre, t.id, k.ordre""",
                    (rs, i) -> new Constante(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), l, l, v.id());
            visites.add(new Visite(v, champs, constantes, ordonnances.lignes(v.id()),
                    examens.liste(ExamenRepository.Genre.ANALYSE, v.id()), examens.liste(ExamenRepository.Genre.IMAGERIE, v.id())));
        }
        Context ctx = new Context(langue);
        ctx.setVariable("racine", racine);
        ctx.setVariable("patient", racine.get("patient"));
        ctx.setVariable("allergies", allergies.liste(patient));
        ctx.setVariable("antecedents", allergies.antecedents(patient));
        ctx.setVariable("visites", visites);
        ctx.setVariable("fichiers", donnees.getOrDefault("fichier", List.of()));
        ctx.setVariable("rendezVous", donnees.getOrDefault("rendez_vous", List.of()));
        return moteur.process("export/dossier", ctx);
    }
}
