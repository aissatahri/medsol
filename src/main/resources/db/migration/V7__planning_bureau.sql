-- Planning repris de SGCM-Cardio : statuts du bureau (En cours, Reporté), journal des rendez-vous, horaires du cabinet.

-- Le bureau permet d'« enregistrer quand même » sur un créneau occupé : le chevauchement est contrôlé par
-- l'application (avec confirmation), plus par la base.
DO $$
DECLARE c text;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint WHERE conrelid = 'rendez_vous'::regclass AND contype IN ('x', 'c') LOOP
        EXECUTE format('ALTER TABLE rendez_vous DROP CONSTRAINT %I', c);
    END LOOP;
END $$;
UPDATE rendez_vous SET statut = 'EN_COURS' WHERE statut = 'ARRIVE';
ALTER TABLE rendez_vous
    ADD CONSTRAINT rendez_vous_statut_chk CHECK (statut IN ('PLANIFIE','CONFIRME','EN_COURS','TERMINE','REPORTE','ANNULE','ABSENT')),
    ADD CONSTRAINT rendez_vous_duree_chk CHECK (fin > debut);

-- Journal des changements de statut et des reports (rendezvous_audit du bureau)
CREATE TABLE rdv_journal (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cabinet_id   uuid        NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    rdv_id       uuid        NOT NULL REFERENCES rendez_vous(id) ON DELETE CASCADE,
    quand        timestamptz NOT NULL DEFAULT now(),
    ancien       text,
    nouveau      text        NOT NULL,
    utilisateur  text,
    detail       text
);
CREATE INDEX rdv_journal_rdv_idx ON rdv_journal (rdv_id, quand DESC);

-- Horaires de consultation du cabinet (disponibilites du bureau) : jour ISO 1 = lundi … 7 = dimanche
CREATE TABLE disponibilite (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cabinet_id  uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    jour        int  NOT NULL CHECK (jour BETWEEN 1 AND 7),
    debut       time NOT NULL,
    fin         time NOT NULL,
    CHECK (fin > debut)
);
CREATE INDEX disponibilite_cabinet_idx ON disponibilite (cabinet_id, jour);

ALTER TABLE rdv_journal   ENABLE ROW LEVEL SECURITY; ALTER TABLE rdv_journal   FORCE ROW LEVEL SECURITY;
ALTER TABLE disponibilite ENABLE ROW LEVEL SECURITY; ALTER TABLE disponibilite FORCE ROW LEVEL SECURITY;
CREATE POLICY rdv_journal_iso   ON rdv_journal   USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet());
CREATE POLICY disponibilite_iso ON disponibilite USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet());
GRANT SELECT, INSERT ON rdv_journal TO medsol_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON disponibilite TO medsol_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO medsol_app;

-- Cabinets de démonstration : lundi–vendredi 09:00–13:00 et 15:00–19:00, samedi 09:00–12:00
INSERT INTO disponibilite (cabinet_id, jour, debut, fin)
SELECT c.id, j, h.debut, h.fin
FROM cabinet c
CROSS JOIN generate_series(1, 5) j
CROSS JOIN (VALUES (time '09:00', time '13:00'), (time '15:00', time '19:00')) AS h(debut, fin)
WHERE c.id IN (SELECT cabinet_id FROM utilisateur WHERE email LIKE '%@demo.medsol')
UNION ALL
SELECT c.id, 6, time '09:00', time '12:00' FROM cabinet c
WHERE c.id IN (SELECT cabinet_id FROM utilisateur WHERE email LIKE '%@demo.medsol');
