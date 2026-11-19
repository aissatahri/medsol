package com.medsol.documents;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Réglages d'impression du cabinet courant (Paramètres › Impression), un jeu par type de document. */
@Service
public class ImpressionReglages {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    public ImpressionReglages(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Mise en page du document pour le cabinet courant (celle par défaut si rien n'est réglé ou illisible). */
    public MiseEnPage pour(String document) {
        MiseEnPage.type(document);
        return jdbc.query("SELECT reglages::text FROM document_reglage WHERE document = ?", (rs, i) -> rs.getString(1), document)
                .stream().findFirst().map(json -> {
                    try {
                        MiseEnPage m = JSON.readValue(json, MiseEnPage.class);
                        return new MiseEnPage(document, m.police(), m.haut(), m.bas(), m.gauche(), m.droite(), m.elements(), false);
                    } catch (RuntimeException e) {
                        return MiseEnPage.defaut(document);
                    }
                }).orElseGet(() -> MiseEnPage.defaut(document));
    }

    public void enregistrer(MiseEnPage m) {
        String json = JSON.writeValueAsString(m.avecGrille(false));
        jdbc.update("""
                INSERT INTO document_reglage (cabinet_id, document, reglages) VALUES (current_cabinet(), ?, ?::jsonb)
                ON CONFLICT (cabinet_id, document) DO UPDATE SET reglages = excluded.reglages, modifie_le = now()""",
                m.document(), json);
    }

    public void reinitialiser(String document) {
        jdbc.update("DELETE FROM document_reglage WHERE document = ?", document);
    }
}
