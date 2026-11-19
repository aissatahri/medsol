package com.medsol.dossier;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Types de champs cliniques et de constantes du cabinet (repris de Catalogues de SGCM-Cardio) : modèles communs,
 * ceux de la spécialité du cabinet et ses propres ajouts. Libellés dans la langue de l'utilisateur.
 */
@Component
public class Catalogues {

    public record TypeChamp(long id, String nom, String label) {}

    public record Cle(String cle, String label, String unite) {}

    public record TypeConstante(long id, String nom, String label, String unite, boolean complexe, List<Cle> cles) {
        /** L'IMC est calculé depuis le poids et la taille, pas saisi. */
        public boolean calcule() { return "IMC".equals(nom); }
    }

    private static final String FILTRE = "actif AND (specialite IS NULL OR specialite = (SELECT specialite FROM cabinet)) "
            + "AND NOT EXISTS (SELECT 1 FROM type_masque m WHERE m.genre = 'CHAMP' AND m.type_id = type_champ_clinique.id)";

    private final JdbcTemplate jdbc;

    public Catalogues(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static String langue() { return LocaleContextHolder.getLocale().getLanguage(); }

    public List<TypeChamp> champs() {
        return jdbc.query("SELECT id, nom, coalesce(libelle->>?, libelle->>'fr') FROM type_champ_clinique WHERE " + FILTRE
                        + " ORDER BY ordre, id",
                (rs, i) -> new TypeChamp(rs.getLong(1), rs.getString(2), rs.getString(3)), langue());
    }

    /** Types de constantes avec leurs sous-clés (bras gauche, bras droit...) en une requête. */
    public List<TypeConstante> constantes() {
        String l = langue();
        Map<Long, TypeConstante> types = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.id, t.nom, coalesce(t.libelle->>?, t.libelle->>'fr'), t.unite, t.complexe,
                       k.cle, coalesce(k.libelle->>?, k.libelle->>'fr'), k.unite
                FROM type_constante t LEFT JOIN type_constante_cle k ON k.type_constante_id = t.id
                WHERE t.actif AND (t.specialite IS NULL OR t.specialite = (SELECT specialite FROM cabinet))
                  AND NOT EXISTS (SELECT 1 FROM type_masque m WHERE m.genre = 'CONSTANTE' AND m.type_id = t.id)
                ORDER BY t.ordre, t.id, k.ordre, k.id""",
                rs -> {
                    long id = rs.getLong(1);
                    TypeConstante t = types.computeIfAbsent(id, k -> {
                        try {
                            return new TypeConstante(id, rs.getString(2), rs.getString(3), rs.getString(4),
                                    rs.getBoolean(5), new ArrayList<>());
                        } catch (java.sql.SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    if (rs.getString(6) != null) t.cles().add(new Cle(rs.getString(6), rs.getString(7), rs.getString(8)));
                }, l, l);
        return List.copyOf(types.values());
    }
}
