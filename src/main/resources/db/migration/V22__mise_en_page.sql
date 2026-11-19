-- Paramètres › Impression : mise en page de chaque document imprimé, propre au cabinet (police, marges, et pour
-- chaque élément : affiché ou non, taille, gras, position fixe en mm pour tomber dans les cases d'une feuille
-- préimprimée). Valeurs absentes = mise en page par défaut de MedSol.
CREATE TABLE document_reglage (
    cabinet_id  uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    document    text NOT NULL CHECK (document IN ('ORDONNANCE','ANALYSE','IMAGERIE','CERTIFICAT','LETTRE','COMPTE_RENDU')),
    reglages    jsonb NOT NULL,
    modifie_le  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (cabinet_id, document)
);
ALTER TABLE document_reglage ENABLE ROW LEVEL SECURITY;
CREATE POLICY document_reglage_iso ON document_reglage USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet());
GRANT SELECT, INSERT, UPDATE, DELETE ON document_reglage TO medsol_app;
