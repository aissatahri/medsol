-- Fiche patient complétée comme le bureau : situation familiale, profession, couverture sanitaire,
-- traitement en cours avant la première consultation.

ALTER TABLE patient
    ADD COLUMN situation_familiale text CHECK (situation_familiale IN ('CELIBATAIRE','MARIE','DIVORCE','VEUF')),
    ADD COLUMN profession          text,
    ADD COLUMN couverture          text,
    ADD COLUMN traitement_avant    text;

-- Couvertures sanitaires proposées à la saisie, par pays (types_couverture_sanitaire du bureau pour le Maroc)
CREATE TABLE couverture_sanitaire (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pays     char(2) NOT NULL,
    libelle  text    NOT NULL,
    UNIQUE (pays, libelle)
);
INSERT INTO couverture_sanitaire (pays, libelle) VALUES
    ('MA','AMO - CNSS'), ('MA','AMO - CNOPS'), ('MA','RAMED'), ('MA','FAR'), ('MA','CMR'), ('MA','MGPAP'),
    ('MA','OMFAM'), ('MA','MFP'), ('MA','Assurance privée'), ('MA','Sans couverture'), ('MA','Autre'),
    ('FR','Assurance maladie (CPAM)'), ('FR','Complémentaire santé (mutuelle)'), ('FR','Complémentaire santé solidaire'),
    ('FR','Aide médicale de l''État (AME)'), ('FR','MSA'), ('FR','Sans couverture'),
    ('SN','IPM'), ('SN','Couverture maladie universelle (CMU)'), ('SN','Assurance privée'), ('SN','Sans couverture'),
    ('US','Medicare'), ('US','Medicaid'), ('US','Private insurance'), ('US','Uninsured');
GRANT SELECT ON couverture_sanitaire TO medsol_app;

-- Salle d'attente : état du patient à l'arrivée (priorité), comme le bureau
ALTER TABLE file_attente
    ADD COLUMN etat text NOT NULL DEFAULT 'NORMAL' CHECK (etat IN ('NORMAL','URGENT','AGE','GRAVE','AUTRE'));