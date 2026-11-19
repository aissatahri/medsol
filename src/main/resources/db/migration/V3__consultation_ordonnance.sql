-- Phase 2 : coordonnées du cabinet, consultations, mesures par spécialité, ordonnances.

ALTER TABLE cabinet
    ADD COLUMN adresse       text,
    ADD COLUMN telephone     text,
    ADD COLUMN numero_ordre  text;      -- n° d'inscription à l'ordre / licence d'exercice

CREATE TABLE consultation (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id    uuid        NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    patient_id    uuid        NOT NULL REFERENCES patient(id),
    medecin_id    uuid        NOT NULL,
    date_consult  timestamptz NOT NULL DEFAULT now(),
    statut        text        NOT NULL DEFAULT 'EN_COURS' CHECK (statut IN ('EN_COURS','SIGNEE')),
    motif         text,
    anamnese      text,
    examen        text,
    diagnostic    text,
    conduite      text,                 -- conduite à tenir / conclusion
    signee_le     timestamptz,
    modifie_le    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX consultation_patient_idx ON consultation (cabinet_id, patient_id, date_consult DESC);

-- Mesures et champs propres à la spécialité (clé = identifiant du modèle de spécialité)
CREATE TABLE consultation_mesure (
    consultation_id uuid NOT NULL REFERENCES consultation(id) ON DELETE CASCADE,
    cabinet_id      uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    cle             text NOT NULL,
    valeur          text NOT NULL,
    PRIMARY KEY (consultation_id, cle)
);

CREATE TABLE prescription (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id      uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    consultation_id uuid NOT NULL REFERENCES consultation(id) ON DELETE CASCADE,
    ordre           int  NOT NULL,
    medicament      text NOT NULL,
    posologie       text,
    duree           text,
    instructions    text
);
CREATE INDEX prescription_consultation_idx ON prescription (consultation_id, ordre);
CREATE INDEX prescription_medicament_idx ON prescription (cabinet_id, lower(medicament));

-- Une consultation signée est figée (valeur médico-légale) : refus en base, quoi que fasse l'application.
CREATE FUNCTION consultation_figee() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'consultation' THEN
        IF OLD.statut = 'SIGNEE' THEN
            RAISE EXCEPTION 'consultation signée : modification interdite' USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    -- mesures et prescriptions : la consultation parente ne doit pas être signée
    IF EXISTS (SELECT 1 FROM consultation c
               WHERE c.id = CASE WHEN TG_OP = 'DELETE' THEN OLD.consultation_id ELSE NEW.consultation_id END
                 AND c.statut = 'SIGNEE') THEN
        RAISE EXCEPTION 'consultation signée : modification interdite' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE TRIGGER consultation_figee BEFORE UPDATE OR DELETE ON consultation
    FOR EACH ROW EXECUTE FUNCTION consultation_figee();
CREATE TRIGGER mesure_figee BEFORE INSERT OR UPDATE OR DELETE ON consultation_mesure
    FOR EACH ROW EXECUTE FUNCTION consultation_figee();
CREATE TRIGGER prescription_figee BEFORE INSERT OR UPDATE OR DELETE ON prescription
    FOR EACH ROW EXECUTE FUNCTION consultation_figee();

ALTER TABLE consultation         ENABLE ROW LEVEL SECURITY; ALTER TABLE consultation         FORCE ROW LEVEL SECURITY;
ALTER TABLE consultation_mesure  ENABLE ROW LEVEL SECURITY; ALTER TABLE consultation_mesure  FORCE ROW LEVEL SECURITY;
ALTER TABLE prescription         ENABLE ROW LEVEL SECURITY; ALTER TABLE prescription         FORCE ROW LEVEL SECURITY;

CREATE POLICY consultation_iso ON consultation        USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet());
CREATE POLICY mesure_iso       ON consultation_mesure USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet());
CREATE POLICY prescription_iso ON prescription        USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet());

GRANT SELECT, INSERT, UPDATE ON consultation TO medsol_app;          -- pas de DELETE : un dossier médical ne s'efface pas
GRANT SELECT, INSERT, UPDATE, DELETE ON consultation_mesure, prescription TO medsol_app;
