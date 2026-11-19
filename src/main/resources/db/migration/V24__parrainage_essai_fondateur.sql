-- Croissance : parrainage entre confrères, e-mails d'accompagnement pendant l'essai, tarif « fondateur ».

-- Parrainage : chaque cabinet a un code ; le filleul inscrit avec ce code est rattaché à son parrain. Au premier
-- paiement du filleul, chacun reçoit un mois offert (une seule fois par filleul).
ALTER TABLE cabinet
    ADD COLUMN code_parrainage          text UNIQUE DEFAULT upper(substr(md5(gen_random_uuid()::text), 1, 7)),
    ADD COLUMN parrain_id               uuid REFERENCES cabinet(id) ON DELETE SET NULL,
    ADD COLUMN parrainage_recompense_le timestamptz,
    ADD COLUMN tarif_fondateur          boolean NOT NULL DEFAULT false;   -- prix « fondateur » garanti à vie
UPDATE cabinet SET code_parrainage = upper(substr(md5(gen_random_uuid()::text), 1, 7)) WHERE code_parrainage IS NULL;

-- E-mails d'accompagnement de l'essai déjà envoyés (J+1, J+3, J+7)
CREATE TABLE essai_email (
    cabinet_id  uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    etape       text NOT NULL CHECK (etape IN ('J1','J3','J7')),
    envoye_le   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (cabinet_id, etape)
);
