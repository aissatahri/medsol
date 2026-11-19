-- Caisse et actes repris de SGCM-Cardio : catalogue des actes, actes réalisés (payé / offert), versements,
-- factures numérotées. Mentions légales du cabinet pour l'en-tête des factures, reçus et clôtures.

ALTER TABLE cabinet
    ADD COLUMN mentions_legales text,                 -- une mention par ligne (ICE, IF, TP, CNSS au Maroc ; SIRET en France…)
    ADD COLUMN identifiant_professionnel text;        -- INPE, RPPS, NPI… : imprimé aussi en code-barres sur la facture

-- Catalogue des actes du cabinet (Paramètres › Actes)
CREATE TABLE acte (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id  uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    nom         text NOT NULL,
    prix        numeric(12,2) NOT NULL CHECK (prix >= 0),
    actif       boolean NOT NULL DEFAULT true
);
CREATE UNIQUE INDEX acte_nom_uq ON acte (cabinet_id, lower(nom));

-- Actes réalisés lors d'une consultation ; le prix est figé au moment de l'acte (un changement de tarif
-- ne modifie pas les consultations passées)
CREATE TABLE consultation_acte (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id      uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    consultation_id uuid NOT NULL REFERENCES consultation(id) ON DELETE CASCADE,
    acte_id         uuid NOT NULL REFERENCES acte(id),
    prix            numeric(12,2) NOT NULL,
    paye            boolean NOT NULL DEFAULT true,     -- false = offert (compte 0)
    UNIQUE (consultation_id, acte_id)
);

-- Versements ; un versement annulé est gardé (annule_le) pour la traçabilité de la caisse
CREATE TABLE versement (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id      uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    consultation_id uuid NOT NULL REFERENCES consultation(id),
    numero          bigint NOT NULL,                   -- n° du reçu (R-n), par cabinet
    montant         numeric(12,2) NOT NULL CHECK (montant > 0),
    mode            text NOT NULL CHECK (mode IN ('ESPECES','CARTE','CHEQUE','VIREMENT','MOBILE')),
    jour            date NOT NULL,                     -- jour local du cabinet
    cree_le         timestamptz NOT NULL DEFAULT now(),
    cree_par        uuid,
    annule_le       timestamptz,
    annule_par      uuid
);
CREATE INDEX versement_consultation_idx ON versement (consultation_id);
CREATE INDEX versement_jour_idx ON versement (cabinet_id, jour);

-- Factures émises : numéro unique, jamais redonné (FA_jjmmaaaa-NNN, compteur du jour)
CREATE TABLE facture (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id  uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    numero      text NOT NULL,
    patient_id  uuid NOT NULL REFERENCES patient(id),
    jour        date NOT NULL,
    total       numeric(12,2) NOT NULL,
    lignes      text NOT NULL,                     -- « acte;montant » par ligne, tel qu'imprimé
    cree_le     timestamptz NOT NULL DEFAULT now(),
    cree_par    uuid,
    UNIQUE (cabinet_id, numero)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['acte','consultation_acte','versement','facture'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON %I USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet())', t || '_iso', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE, DELETE ON acte, consultation_acte TO medsol_app;
GRANT SELECT, INSERT, UPDATE ON versement TO medsol_app;      -- pas de DELETE : une annulation se trace
GRANT SELECT, INSERT ON facture TO medsol_app;                -- une facture émise ne change plus

-- Cabinets de démonstration : catalogue d'actes selon la spécialité
INSERT INTO acte (cabinet_id, nom, prix)
SELECT c.id, a.nom, a.prix FROM cabinet c
JOIN (VALUES
    ('CARDIO', 'Consultation', 300), ('CARDIO', 'ECG', 200), ('CARDIO', 'Échocardiographie', 600), ('CARDIO', 'Holter tensionnel', 500),
    ('GENERAL', 'Consultation', 26.50), ('GENERAL', 'Visite à domicile', 36.50), ('GENERAL', 'Certificat médical', 25),
    ('PEDIATRIE', 'Consultation', 10000), ('PEDIATRIE', 'Vaccination', 5000), ('PEDIATRIE', 'Suivi du nourrisson', 8000)
) AS a(specialite, nom, prix) ON a.specialite = c.specialite
WHERE c.id IN (SELECT cabinet_id FROM utilisateur WHERE email LIKE '%@demo.medsol');
