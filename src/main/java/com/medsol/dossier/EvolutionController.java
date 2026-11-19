package com.medsol.dossier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Courbes des constantes (repris d'EvolutionController de SGCM-Cardio, SVG dessiné par le serveur). Généralisé à
 * toutes les spécialités : une courbe par constante chiffrée du cabinet (poids, périmètre crânien, tonus oculaire…),
 * la tension en systolique / diastolique pour chaque bras. Médecin (/patients/{id}/dossier…).
 */
@Controller
public class EvolutionController {

    public record Point(double x, double y, String bulle) {}

    public record Serie(String nom, String classe, String trace, List<Point> points, String derniere) {}

    public record Graduation(double y, String libelle) {}

    public record Graphique(String titre, String unite, List<Serie> series, List<Graduation> graduations, List<String[]> dates,
                            int largeur, int hauteur) {
        public String vue() { return "0 0 " + largeur + " " + hauteur; }
    }

    private static final int LARGEUR = 520, HAUTEUR = 220, MARGE_G = 42, MARGE_D = 14, MARGE_H = 14, MARGE_B = 28;
    private static final DateTimeFormatter COURT = DateTimeFormatter.ofPattern("dd/MM/yy");

    private final JdbcTemplate jdbc;
    private final Catalogues catalogues;

    public EvolutionController(JdbcTemplate jdbc, Catalogues catalogues) {
        this.jdbc = jdbc;
        this.catalogues = catalogues;
    }

    @GetMapping("/patients/{id}/dossier/evolution")
    public String evolution(@PathVariable UUID id, Model model) {
        // date → « TYPE » ou « TYPE.CLÉ » → valeur
        TreeMap<LocalDate, Map<String, String>> mesures = new TreeMap<>();
        jdbc.query("""
                SELECT c.date_consult, t.nom, cc.cle, cc.valeur FROM consultation_constante cc
                JOIN consultation c ON c.id = cc.consultation_id JOIN type_constante t ON t.id = cc.type_id
                WHERE c.patient_id = ? AND c.supprimee_le IS NULL ORDER BY c.date_consult""", rs -> {
            String cle = rs.getString(3).isEmpty() ? rs.getString(2) : rs.getString(2) + "." + rs.getString(3);
            mesures.computeIfAbsent(rs.getObject(1, LocalDate.class), k -> new HashMap<>()).put(cle, rs.getString(4));
        }, id);

        List<Graphique> graphiques = new ArrayList<>();
        int couleur = 0;
        for (Catalogues.TypeConstante t : catalogues.constantes()) {
            Map<String, TreeMap<LocalDate, Double>> lignes = new LinkedHashMap<>();
            if (t.complexe()) {
                for (Catalogues.Cle k : t.cles()) {
                    String cle = t.nom() + "." + k.cle();
                    String classe = "serie-" + (couleur % 6 + 1);
                    if ("PRESSION_ARTERIELLE".equals(t.nom())) {
                        TreeMap<LocalDate, Double> sys = new TreeMap<>(), dia = new TreeMap<>();
                        mesures.forEach((d, v) -> {
                            double[] ta = tensionMmHg(v.get(cle));
                            if (ta != null) { sys.put(d, ta[0]); dia.put(d, ta[1]); }
                        });
                        if (!sys.isEmpty()) {
                            lignes.put(k.label() + " · systolique|" + classe, sys);
                            lignes.put(k.label() + " · diastolique|" + classe + " pointilles", dia);
                            couleur++;
                        }
                    } else {
                        TreeMap<LocalDate, Double> serie = serie(mesures, cle);
                        if (!serie.isEmpty()) { lignes.put(k.label() + "|" + classe, serie); couleur++; }
                    }
                }
            } else {
                TreeMap<LocalDate, Double> serie = serie(mesures, t.nom());
                if (!serie.isEmpty()) { lignes.put(t.label() + "|serie-" + (couleur % 6 + 1), serie); couleur++; }
            }
            if (!lignes.isEmpty()) {
                graphiques.add(graphique(t.label(), "PRESSION_ARTERIELLE".equals(t.nom()) ? "mm Hg" : t.unite() == null ? "" : t.unite(),
                        lignes, List.copyOf(mesures.keySet())));
            }
        }
        model.addAttribute("graphiques", graphiques);
        model.addAttribute("nbConsultations", mesures.size());
        return "dossier/evolution :: evolution";
    }

    private static TreeMap<LocalDate, Double> serie(TreeMap<LocalDate, Map<String, String>> mesures, String cle) {
        TreeMap<LocalDate, Double> s = new TreeMap<>();
        mesures.forEach((d, v) -> {
            Double n = nombre(v.get(cle));
            if (n != null && n > 0) s.put(d, n);
        });
        return s;
    }

    /** Axe des x commun : toutes les consultations avec mesures, dans l'ordre (écart constant). */
    private static Graphique graphique(String titre, String unite, Map<String, TreeMap<LocalDate, Double>> lignes, List<LocalDate> axe) {
        List<LocalDate> dates = axe.stream().filter(d -> lignes.values().stream().anyMatch(s -> s.containsKey(d))).toList();
        double min = lignes.values().stream().flatMap(s -> s.values().stream()).mapToDouble(Double::doubleValue).min().orElse(0);
        double max = lignes.values().stream().flatMap(s -> s.values().stream()).mapToDouble(Double::doubleValue).max().orElse(1);
        double marge = Math.max((max - min) * 0.15, Math.max(0.1, Math.abs(max) * 0.05));
        double bas = Math.max(0, Math.floor(min - marge));
        double haut = Math.ceil(max + marge);
        double zoneL = LARGEUR - MARGE_G - MARGE_D;
        double zoneH = HAUTEUR - MARGE_H - MARGE_B;
        java.util.function.IntFunction<Double> xDe = i -> MARGE_G + (dates.size() == 1 ? zoneL / 2 : zoneL * i / (dates.size() - 1));
        java.util.function.DoubleUnaryOperator yDe = v -> MARGE_H + zoneH * (1 - (v - bas) / (haut - bas));
        List<Serie> series = new ArrayList<>();
        lignes.forEach((nomClasse, valeurs) -> {
            String[] nc = nomClasse.split("\\|");
            StringBuilder trace = new StringBuilder();
            List<Point> points = new ArrayList<>();
            for (int i = 0; i < dates.size(); i++) {
                Double v = valeurs.get(dates.get(i));
                if (v == null) continue;
                double x = xDe.apply(i);
                double y = yDe.applyAsDouble(v);
                trace.append(trace.isEmpty() ? "M" : " L").append(arrondi(x)).append(' ').append(arrondi(y));
                points.add(new Point(arrondi(x), arrondi(y), dates.get(i).format(COURT) + " : " + texte(v) + " " + unite));
            }
            series.add(new Serie(nc[0], nc[1], trace.toString(), points,
                    texte(valeurs.lastEntry().getValue()) + " " + unite + " le " + valeurs.lastKey().format(COURT)));
        });
        List<Graduation> graduations = new ArrayList<>();
        for (int k = 0; k <= 3; k++) {
            double v = bas + (haut - bas) * k / 3;
            graduations.add(new Graduation(arrondi(yDe.applyAsDouble(v)), texte(Math.round(v * 10) / 10.0)));
        }
        List<String[]> etiquettes = new ArrayList<>();
        int pas = Math.max(1, (int) Math.ceil(dates.size() / 6.0));
        for (int i = 0; i < dates.size(); i += pas) etiquettes.add(new String[]{String.valueOf(arrondi(xDe.apply(i))), dates.get(i).format(COURT)});
        if (dates.size() > 1 && (dates.size() - 1) % pas != 0) {
            etiquettes.add(new String[]{String.valueOf(arrondi(xDe.apply(dates.size() - 1))), dates.get(dates.size() - 1).format(COURT)});
        }
        return new Graphique(titre, unite, series, graduations, etiquettes, LARGEUR, HAUTEUR);
    }

    /** « 158/57 » ou « 15/8 » (cm Hg, ramené en mm Hg) → {systolique, diastolique}. */
    static double[] tensionMmHg(String texte) {
        if (texte == null || !texte.contains("/")) return null;
        String[] p = texte.replace(',', '.').split("/");
        Double s = nombre(p[0]);
        Double d = p.length > 1 ? nombre(p[1]) : null;
        if (s == null || d == null || s <= 0 || d <= 0) return null;
        if (s < 30) { s = s * 10; d = d < 30 ? d * 10 : d; }
        return new double[]{s, d};
    }

    private static Double nombre(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s.strip().replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    private static double arrondi(double v) { return Math.round(v * 10) / 10.0; }

    private static String texte(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }
}
