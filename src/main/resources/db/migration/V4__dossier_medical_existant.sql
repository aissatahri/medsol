-- Dossier médical repris de SGCM-Cardio (bureau + web), adapté au multi-cabinet et au multi-spécialité.
-- Remplace le modèle simplifié de V3 (consultation figée par signature, mesures en clé/valeur) qui n'existait
-- dans aucune des deux applications.

DROP TABLE IF EXISTS prescription CASCADE;
DROP TABLE IF EXISTS consultation_mesure CASCADE;
DROP TABLE IF EXISTS consultation CASCADE;
DROP FUNCTION IF EXISTS consultation_figee() CASCADE;

-- ═══ Paramétrage (comme les tables type_* du bureau) ═══════════════════════════════════════════
-- cabinet_id NULL = modèle fourni par MedSol (visible de tous) ; sinon ajout propre au cabinet.
-- specialite NULL = commun à toutes les spécialités.

CREATE TABLE type_constante (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cabinet_id  uuid REFERENCES cabinet(id) ON DELETE CASCADE,
    specialite  text REFERENCES specialite(code),
    nom         text    NOT NULL,                 -- code : POIDS, PRESSION_ARTERIELLE...
    libelle     jsonb   NOT NULL,                 -- {"fr": "Poids", "en": "Weight"}
    unite       text,
    complexe    boolean NOT NULL DEFAULT false,   -- plusieurs sous-valeurs (bras gauche / droit...)
    ordre       int     NOT NULL DEFAULT 0,
    actif       boolean NOT NULL DEFAULT true
);
CREATE UNIQUE INDEX type_constante_nom_uq ON type_constante (coalesce(cabinet_id, '00000000-0000-0000-0000-000000000000'), nom);

CREATE TABLE type_constante_cle (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type_constante_id  bigint NOT NULL REFERENCES type_constante(id) ON DELETE CASCADE,
    cle                text   NOT NULL,
    libelle            jsonb  NOT NULL,
    unite              text,
    ordre              int    NOT NULL DEFAULT 0,
    UNIQUE (type_constante_id, cle)
);

CREATE TABLE type_champ_clinique (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cabinet_id  uuid REFERENCES cabinet(id) ON DELETE CASCADE,
    specialite  text REFERENCES specialite(code),
    nom         text    NOT NULL,
    libelle     jsonb   NOT NULL,
    ordre       int     NOT NULL DEFAULT 0,
    actif       boolean NOT NULL DEFAULT true
);
CREATE UNIQUE INDEX type_champ_nom_uq ON type_champ_clinique (coalesce(cabinet_id, '00000000-0000-0000-0000-000000000000'), nom);

-- Catalogue des médicaments : par pays (référentiel national) + ajouts du cabinet
CREATE TABLE medicament (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pays          char(2),
    cabinet_id    uuid REFERENCES cabinet(id) ON DELETE CASCADE,
    nom           text NOT NULL,
    dci           text,
    forme         text,                 -- forme et dosage : « COMPRIME à 5 MG »
    presentation  text,
    classe        text,
    prix_public   numeric(12,2),
    CHECK (pays IS NOT NULL OR cabinet_id IS NOT NULL)
);
CREATE INDEX medicament_recherche_idx ON medicament USING gin (lower(nom || ' ' || coalesce(dci, '') || ' ' || coalesce(forme, '')) gin_trgm_ops);
CREATE INDEX medicament_pays_idx ON medicament (pays);

-- Catalogue des analyses et examens d'imagerie (types_analyse / types_imagerie du bureau)
CREATE TABLE type_examen (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    genre       text NOT NULL CHECK (genre IN ('ANALYSE','IMAGERIE')),
    cabinet_id  uuid REFERENCES cabinet(id) ON DELETE CASCADE,
    nom         jsonb NOT NULL,         -- {"fr": "Numération Formule Sanguine", "en": "Complete Blood Count"}
    code        jsonb NOT NULL DEFAULT '{}'   -- {"fr": "NFS", "en": "CBC"} : imprimé sur la demande
);
CREATE INDEX type_examen_genre_idx ON type_examen (genre);

-- ═══ Dossier du patient ═══════════════════════════════════════════════════════════════════════

CREATE TABLE antecedent (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id  uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    patient_id  uuid NOT NULL REFERENCES patient(id),
    type        text NOT NULL CHECK (type IN ('MEDICAL','CHIRURGICAL','FAMILIAL','SOCIAL','AUTRE')),
    description text NOT NULL,
    date_debut  date,
    note        text,
    cree_le     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX antecedent_patient_idx ON antecedent (patient_id);

CREATE TABLE allergie (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id      uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    patient_id      uuid NOT NULL REFERENCES patient(id),
    nom             text NOT NULL,
    date_detection  date,
    cree_le         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX allergie_patient_idx ON allergie (patient_id);

-- Une consultation par patient et par jour (règle du bureau) ; suppression = corbeille
CREATE TABLE consultation (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id     uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    patient_id     uuid NOT NULL REFERENCES patient(id),
    medecin_id     uuid NOT NULL,
    date_consult   date NOT NULL,
    motif          text,
    supprimee_le   timestamptz,
    motif_suppression text,
    cree_le        timestamptz NOT NULL DEFAULT now(),
    modifie_le     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX consultation_jour_uq ON consultation (cabinet_id, patient_id, date_consult) WHERE supprimee_le IS NULL;
CREATE INDEX consultation_patient_idx ON consultation (patient_id, date_consult DESC);

CREATE TABLE consultation_champ (
    consultation_id uuid   NOT NULL REFERENCES consultation(id) ON DELETE CASCADE,
    cabinet_id      uuid   NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    type_id         bigint NOT NULL REFERENCES type_champ_clinique(id),
    valeur          text   NOT NULL,
    PRIMARY KEY (consultation_id, type_id)
);

-- Constante simple : cle = '' ; complexe : une ligne par sous-clé (BRAS_GAUCHE...)
CREATE TABLE consultation_constante (
    consultation_id uuid   NOT NULL REFERENCES consultation(id) ON DELETE CASCADE,
    cabinet_id      uuid   NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    type_id         bigint NOT NULL REFERENCES type_constante(id),
    cle             text   NOT NULL DEFAULT '',
    valeur          text   NOT NULL,
    PRIMARY KEY (consultation_id, type_id, cle)
);

CREATE TABLE prescription (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id      uuid   NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    consultation_id uuid   NOT NULL REFERENCES consultation(id) ON DELETE CASCADE,
    medicament_id   bigint NOT NULL REFERENCES medicament(id),
    dose            text   NOT NULL DEFAULT '',
    duree           text   NOT NULL DEFAULT '',
    remarque        text   NOT NULL DEFAULT '',
    ordre           bigint GENERATED ALWAYS AS IDENTITY
);
CREATE INDEX prescription_consultation_idx ON prescription (consultation_id, ordre);

CREATE TABLE ordonnance_type (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id  uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    nom         text NOT NULL,
    cree_le     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ordonnance_type_nom_uq ON ordonnance_type (cabinet_id, lower(nom));

CREATE TABLE ordonnance_type_ligne (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id     uuid   NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    type_id        uuid   NOT NULL REFERENCES ordonnance_type(id) ON DELETE CASCADE,
    medicament_id  bigint NOT NULL REFERENCES medicament(id),
    dose           text,
    duree          text,
    remarque       text,
    ordre          int    NOT NULL DEFAULT 0
);

-- Analyses et imagerie demandées pendant une consultation (tables analyses / radiographies du bureau)
CREATE TABLE examen (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id      uuid   NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    consultation_id uuid   NOT NULL REFERENCES consultation(id) ON DELETE CASCADE,
    genre           text   NOT NULL CHECK (genre IN ('ANALYSE','IMAGERIE')),
    type_id         bigint REFERENCES type_examen(id),
    description     text,                 -- consigne imprimée : « à jeun »
    date_examen     date,
    resultat        text,
    ordre           bigint GENERATED ALWAYS AS IDENTITY
);
CREATE INDEX examen_consultation_idx ON examen (consultation_id, genre, ordre);

-- ═══ Modèles fournis (repris du paramétrage du bureau) ═════════════════════════════════════════

INSERT INTO type_constante (specialite, nom, libelle, unite, complexe, ordre) VALUES
    (NULL, 'POIDS',                  '{"fr":"Poids","en":"Weight"}',                       'kg',     false, 10),
    (NULL, 'TAILLE',                 '{"fr":"Taille","en":"Height"}',                      'm',      false, 20),
    (NULL, 'IMC',                    '{"fr":"IMC","en":"BMI"}',                            'kg/m²',  false, 30),
    (NULL, 'TEMPERATURE',            '{"fr":"Température","en":"Temperature"}',            '°C',     false, 40),
    (NULL, 'FREQUENCE_CARDIAQUE',    '{"fr":"FC (pouls)","en":"Heart rate"}',              'bpm',    false, 50),
    (NULL, 'PRESSION_ARTERIELLE',    '{"fr":"Tension artérielle","en":"Blood pressure"}',  'mm Hg',  true,  60),
    (NULL, 'FREQUENCE_RESPIRATOIRE', '{"fr":"Fré. respiratoire","en":"Respiratory rate"}', 'cpm',    false, 70),
    (NULL, 'SAO2',                   '{"fr":"SaO2","en":"SpO2"}',                          '%',      false, 80),
    (NULL, 'GLYCEMIE',               '{"fr":"Glycémie","en":"Blood glucose"}',             'g/l',    false, 90),
    ('PEDIATRIE', 'PERIMETRE_CRANIEN', '{"fr":"Périmètre crânien","en":"Head circumference"}', 'cm', false, 25),
    ('OPHTALMO',  'ACUITE_VISUELLE',   '{"fr":"Acuité visuelle","en":"Visual acuity"}',       '/10', true, 100),
    ('OPHTALMO',  'TONUS_OCULAIRE',    '{"fr":"Tonus oculaire","en":"Intraocular pressure"}', 'mm Hg', true, 110);

INSERT INTO type_constante_cle (type_constante_id, cle, libelle, unite, ordre)
SELECT t.id, k.cle, k.libelle::jsonb, k.unite, k.ordre
FROM type_constante t
JOIN (VALUES
    ('PRESSION_ARTERIELLE', 'BRAS_GAUCHE', '{"fr":"AT Bras G","en":"BP left arm"}',  'mm Hg', 1),
    ('PRESSION_ARTERIELLE', 'BRAS_DROIT',  '{"fr":"AT Bras D","en":"BP right arm"}', 'mm Hg', 2),
    ('ACUITE_VISUELLE',     'OD',          '{"fr":"OD","en":"Right eye"}',           '/10',   1),
    ('ACUITE_VISUELLE',     'OG',          '{"fr":"OG","en":"Left eye"}',            '/10',   2),
    ('TONUS_OCULAIRE',      'OD',          '{"fr":"OD","en":"Right eye"}',           'mm Hg', 1),
    ('TONUS_OCULAIRE',      'OG',          '{"fr":"OG","en":"Left eye"}',            'mm Hg', 2)
) AS k(nom, cle, libelle, unite, ordre) ON k.nom = t.nom AND t.cabinet_id IS NULL;

INSERT INTO type_champ_clinique (specialite, nom, libelle, ordre) VALUES
    (NULL,        'SYMPTOMES',          '{"fr":"Symptômes","en":"Symptoms"}',                        1),
    (NULL,        'EXAMEN_CLINIQUE',    '{"fr":"Examen clinique","en":"Clinical examination"}',      2),
    (NULL,        'DIAGNOSTIC_MEDICAL', '{"fr":"Diagnostic médical","en":"Diagnosis"}',              3),
    (NULL,        'CONDUITE_TENIR',     '{"fr":"Conduite à tenir","en":"Plan"}',                     4),
    ('CARDIO',    'ECG',                '{"fr":"ECG","en":"ECG"}',                                   5),
    ('CARDIO',    'ETT',                '{"fr":"ETT / Échocardiographie","en":"Echocardiography"}',  6),
    ('PEDIATRIE', 'DEVELOPPEMENT',      '{"fr":"Développement psychomoteur","en":"Development"}',    5),
    ('PEDIATRIE', 'VACCINATION',        '{"fr":"Vaccination","en":"Immunization"}',                  6),
    ('GYNECO',    'DDR',                '{"fr":"Date des dernières règles","en":"Last menstrual period"}', 5),
    ('GYNECO',    'GESTITE_PARITE',     '{"fr":"Gestité / parité","en":"Gravidity / parity"}',       6),
    ('GYNECO',    'EXAMEN_GYNECO',      '{"fr":"Examen gynécologique","en":"Gynecological exam"}',   7),
    ('DERMATO',   'LESIONS',            '{"fr":"Description des lésions","en":"Skin lesions"}',      5),
    ('DENTAIRE',  'SCHEMA_DENTAIRE',    '{"fr":"Dents concernées","en":"Teeth involved"}',           5),
    ('DENTAIRE',  'SOINS_DENTAIRES',    '{"fr":"Soins réalisés","en":"Dental procedures"}',          6),
    ('OPHTALMO',  'FOND_OEIL',          '{"fr":"Fond d''œil","en":"Fundus"}',                        5),
    ('OPHTALMO',  'REFRACTION',         '{"fr":"Réfraction","en":"Refraction"}',                     6);

-- ═══ Sécurité ══════════════════════════════════════════════════════════════════════════════════

-- Paramétrage et catalogues : modèles communs visibles de tous, ajouts limités au cabinet
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['type_constante','type_champ_clinique','medicament','type_examen'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON %I USING (cabinet_id IS NULL OR cabinet_id = current_cabinet()) '
                       'WITH CHECK (cabinet_id = current_cabinet())', t || '_iso', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO medsol_app', t);
    END LOOP;
    FOREACH t IN ARRAY ARRAY['antecedent','allergie','consultation','consultation_champ','consultation_constante',
                             'prescription','ordonnance_type','ordonnance_type_ligne','examen'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON %I USING (cabinet_id = current_cabinet()) '
                       'WITH CHECK (cabinet_id = current_cabinet())', t || '_iso', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO medsol_app', t);
    END LOOP;
END $$;
-- Sous-clés : suivent leur constante (pas de cabinet_id propre)
GRANT SELECT, INSERT, UPDATE, DELETE ON type_constante_cle TO medsol_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO medsol_app;
