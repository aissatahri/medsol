-- Documents conformes au Maroc : en-tête imprimé par MedSol et identifiants fiscaux structurés.
ALTER TABLE cabinet
    ADD COLUMN ville                text,                               -- « Fait à <ville>, le … »
    ADD COLUMN identifiant_fiscal   text,                               -- IF (Maroc)
    ADD COLUMN ice                  text CHECK (ice IS NULL OR ice ~ '^[0-9]{15}$'),  -- Identifiant commun de l'entreprise
    ADD COLUMN taxe_professionnelle text,                               -- TP (ex-patente)
    ADD COLUMN papier_entete        boolean NOT NULL DEFAULT false;     -- true : feuilles préimprimées, MedSol n'imprime pas l'en-tête

-- Ville déduite de l'adresse existante (« 12 boulevard Mohammed V, Oujda » → « Oujda »)
UPDATE cabinet SET ville = nullif(btrim(regexp_replace(adresse, '^.*,', '')), '')
WHERE ville IS NULL AND adresse LIKE '%,%';
