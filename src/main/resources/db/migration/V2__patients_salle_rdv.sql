-- Phase 1 : patients (numérotation, recherche), salle d'attente, rendez-vous.

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Compteurs atomiques par cabinet (n° de dossier, n° de ticket du jour...)
CREATE TABLE cabinet_compteur (
    cabinet_id  uuid   NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    nom         text   NOT NULL,
    valeur      bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (cabinet_id, nom)
);

ALTER TABLE patient
    ADD COLUMN numero          bigint,
    ADD COLUMN consentement_le timestamptz,   -- consentement au traitement des données (RGPD)
    ADD COLUMN cree_par        uuid;
CREATE UNIQUE INDEX patient_numero_uq ON patient (cabinet_id, numero);
CREATE INDEX patient_recherche_idx ON patient USING gin (lower(nom || ' ' || prenom) gin_trgm_ops);
CREATE INDEX patient_tel_idx ON patient (cabinet_id, telephone);

-- Rendez-vous : un seul médecin par cabinet => deux rendez-vous actifs ne peuvent pas se chevaucher
CREATE TABLE rendez_vous (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id  uuid        NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    patient_id  uuid        NOT NULL REFERENCES patient(id),
    debut       timestamptz NOT NULL,
    fin         timestamptz NOT NULL,
    statut      text        NOT NULL DEFAULT 'PLANIFIE'
                CHECK (statut IN ('PLANIFIE','CONFIRME','ARRIVE','TERMINE','ANNULE','ABSENT')),
    motif       text,
    notes       text,
    cree_par    uuid,
    cree_le     timestamptz NOT NULL DEFAULT now(),
    CHECK (fin > debut),
    EXCLUDE USING gist (cabinet_id WITH =, tstzrange(debut, fin) WITH &&)
        WHERE (statut NOT IN ('ANNULE','ABSENT'))
);
CREATE INDEX rdv_cabinet_debut_idx ON rendez_vous (cabinet_id, debut);
CREATE INDEX rdv_patient_idx ON rendez_vous (patient_id);

-- Salle d'attente
CREATE TABLE file_attente (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id  uuid        NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    patient_id  uuid        NOT NULL REFERENCES patient(id),
    jour        date        NOT NULL,           -- jour local du cabinet
    numero      int         NOT NULL,           -- ticket du jour
    statut      text        NOT NULL DEFAULT 'EN_ATTENTE'
                CHECK (statut IN ('EN_ATTENTE','EN_CONSULTATION','TERMINE','ABSENT')),
    motif       text,
    rdv_id      uuid REFERENCES rendez_vous(id),
    arrive_le   timestamptz NOT NULL DEFAULT now(),
    debut_le    timestamptz,
    fin_le      timestamptz,
    UNIQUE (cabinet_id, jour, numero)
);
-- un patient ne peut être qu'une fois en cours dans la salle ; un seul patient en consultation à la fois
CREATE UNIQUE INDEX file_patient_actif_uq ON file_attente (cabinet_id, patient_id, jour)
    WHERE statut IN ('EN_ATTENTE','EN_CONSULTATION');
CREATE UNIQUE INDEX file_consultation_uq ON file_attente (cabinet_id)
    WHERE statut = 'EN_CONSULTATION';
CREATE INDEX file_jour_idx ON file_attente (cabinet_id, jour);

-- Row-Level Security
ALTER TABLE cabinet_compteur ENABLE ROW LEVEL SECURITY; ALTER TABLE cabinet_compteur FORCE ROW LEVEL SECURITY;
ALTER TABLE rendez_vous      ENABLE ROW LEVEL SECURITY; ALTER TABLE rendez_vous      FORCE ROW LEVEL SECURITY;
ALTER TABLE file_attente     ENABLE ROW LEVEL SECURITY; ALTER TABLE file_attente     FORCE ROW LEVEL SECURITY;

CREATE POLICY compteur_iso ON cabinet_compteur USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet());
CREATE POLICY rdv_iso      ON rendez_vous      USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet());
CREATE POLICY file_iso     ON file_attente     USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet());

GRANT SELECT, INSERT, UPDATE ON cabinet_compteur TO medsol_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON rendez_vous, file_attente TO medsol_app;

-- Journal d'audit : medsol_app n'a que SELECT et INSERT (V1) ; il ne peut ni modifier ni effacer une trace.
