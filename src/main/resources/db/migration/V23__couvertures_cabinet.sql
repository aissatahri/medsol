-- Couvertures sanitaires : chaque cabinet peut ajouter les siennes (mutuelle d'entreprise, assureur…), en plus de la
-- liste commune du pays ; la liste marocaine est complétée (AMO Tadamon remplace le RAMED depuis fin 2022).
ALTER TABLE couverture_sanitaire ADD COLUMN cabinet_id uuid REFERENCES cabinet(id) ON DELETE CASCADE;
ALTER TABLE couverture_sanitaire DROP CONSTRAINT couverture_sanitaire_pays_libelle_key;
CREATE UNIQUE INDEX couverture_sanitaire_uq
    ON couverture_sanitaire (coalesce(cabinet_id, '00000000-0000-0000-0000-000000000000'), pays, lower(libelle));

INSERT INTO couverture_sanitaire (pays, libelle) VALUES
    ('MA', 'AMO Tadamon (ex-RAMED)'), ('MA', 'AMO - Travailleurs non salariés (TNS)'), ('MA', 'Mutuelle complémentaire')
ON CONFLICT DO NOTHING;

-- Liste commune lisible par tous ; ajouts et suppressions limités aux couvertures du cabinet
ALTER TABLE couverture_sanitaire ENABLE ROW LEVEL SECURITY;
CREATE POLICY couverture_lecture ON couverture_sanitaire FOR SELECT USING (cabinet_id IS NULL OR cabinet_id = current_cabinet());
CREATE POLICY couverture_ajout ON couverture_sanitaire FOR INSERT WITH CHECK (cabinet_id = current_cabinet());
CREATE POLICY couverture_suppression ON couverture_sanitaire FOR DELETE USING (cabinet_id = current_cabinet());
GRANT INSERT, DELETE ON couverture_sanitaire TO medsol_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO medsol_app;
