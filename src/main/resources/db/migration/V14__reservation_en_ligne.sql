-- Prise de rendez-vous en ligne : page publique du cabinet (/r/<lien>), demandes validées par le cabinet.

ALTER TABLE cabinet
    ADD COLUMN reservation_active  boolean NOT NULL DEFAULT false,
    ADD COLUMN reservation_lien    text UNIQUE CHECK (reservation_lien ~ '^[a-z0-9][a-z0-9-]{2,59}$'),
    ADD COLUMN reservation_duree   int  NOT NULL DEFAULT 20 CHECK (reservation_duree BETWEEN 5 AND 120),    -- minutes
    ADD COLUMN reservation_delai   int  NOT NULL DEFAULT 2  CHECK (reservation_delai BETWEEN 0 AND 168),    -- heures avant le créneau
    ADD COLUMN reservation_horizon int  NOT NULL DEFAULT 30 CHECK (reservation_horizon BETWEEN 1 AND 180), -- jours ouverts à la réservation
    ADD COLUMN reservation_message text;

-- Demande d'un patient : tient le créneau jusqu'à la réponse du cabinet. Créée par la page publique (connexion du
-- propriétaire, cabinet indiqué explicitement) ; lue et traitée par le cabinet (RLS).
CREATE TABLE reservation (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id  uuid        NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    debut       timestamptz NOT NULL,
    fin         timestamptz NOT NULL,
    nom         text        NOT NULL,
    prenom      text        NOT NULL,
    telephone   text        NOT NULL,
    email       text,
    motif       text,
    langue      text        NOT NULL DEFAULT 'fr',
    statut      text        NOT NULL DEFAULT 'EN_ATTENTE' CHECK (statut IN ('EN_ATTENTE','ACCEPTEE','REFUSEE','ANNULEE')),
    jeton       text        NOT NULL UNIQUE,            -- empreinte SHA-256 du lien d'annulation envoyé au patient
    ip          text,
    cree_le     timestamptz NOT NULL DEFAULT now(),
    traite_le   timestamptz,
    traite_par  uuid,
    patient_id  uuid REFERENCES patient(id),
    rdv_id      uuid REFERENCES rendez_vous(id) ON DELETE SET NULL,
    CHECK (fin > debut)
);
CREATE INDEX reservation_cabinet_idx ON reservation (cabinet_id, statut, debut);
-- deux demandes en attente ne peuvent pas viser le même créneau (deux patients au même instant)
CREATE UNIQUE INDEX reservation_creneau_uq ON reservation (cabinet_id, debut) WHERE statut = 'EN_ATTENTE';

ALTER TABLE reservation ENABLE ROW LEVEL SECURITY;
CREATE POLICY reservation_iso ON reservation USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet());
GRANT SELECT, UPDATE ON reservation TO medsol_app;
