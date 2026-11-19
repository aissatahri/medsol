-- Lots 1 à 4 : corbeille (qui a supprimé), fichiers joints, comptes rendus et courriers, messagerie du cabinet,
-- rappels de rendez-vous.

ALTER TABLE consultation ADD COLUMN supprimee_par uuid;

-- Fichiers joints au dossier (scans, résultats, comptes rendus reçus) ; contenu en base, 10 Mo au plus
CREATE TABLE fichier (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id      uuid   NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    patient_id      uuid   NOT NULL REFERENCES patient(id),
    consultation_id uuid   REFERENCES consultation(id) ON DELETE SET NULL,
    nom             text   NOT NULL,
    categorie       text   NOT NULL DEFAULT 'AUTRE'
                    CHECK (categorie IN ('RESULTAT_ANALYSE','COMPTE_RENDU_ANALYSE','IMAGERIE','COMPTE_RENDU_IMAGERIE','COURRIER','ORDONNANCE','AUTRE')),
    type_mime       text   NOT NULL,
    taille          int    NOT NULL CHECK (taille > 0 AND taille <= 10485760),
    contenu         bytea  NOT NULL,
    cree_le         timestamptz NOT NULL DEFAULT now(),
    cree_par        uuid
);
CREATE INDEX fichier_patient_idx ON fichier (patient_id, cree_le DESC);

-- Comptes rendus par spécialité (ETT en cardiologie…) et lettres de référence, rédigés depuis une consultation
CREATE TABLE redaction (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id      uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    consultation_id uuid NOT NULL REFERENCES consultation(id) ON DELETE CASCADE,
    modele          text NOT NULL,          -- LETTRE, ETT, ECHO_OBSTETRICALE, …
    destinataire    text,
    contenu         text NOT NULL,
    conclusion      text,
    modifie_le      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (consultation_id, modele)
);

-- Messagerie du cabinet (médecin ↔ assistante)
CREATE TABLE message (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cabinet_id  uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    auteur_id   uuid NOT NULL REFERENCES utilisateur(id) ON DELETE CASCADE,
    texte       text NOT NULL CHECK (length(texte) <= 900),
    cree_le     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX message_cabinet_idx ON message (cabinet_id, id DESC);
CREATE TABLE message_lu (
    utilisateur_id uuid PRIMARY KEY REFERENCES utilisateur(id) ON DELETE CASCADE,
    cabinet_id     uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    dernier        bigint NOT NULL DEFAULT 0
);

-- Rappels de rendez-vous envoyés (WhatsApp, SMS) : pour ne pas rappeler deux fois
CREATE TABLE rdv_rappel (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cabinet_id  uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    rdv_id      uuid NOT NULL REFERENCES rendez_vous(id) ON DELETE CASCADE,
    canal       text NOT NULL CHECK (canal IN ('WHATSAPP','SMS','APPEL')),
    envoye_le   timestamptz NOT NULL DEFAULT now(),
    par         uuid
);
CREATE INDEX rdv_rappel_rdv_idx ON rdv_rappel (rdv_id);

-- Paramètres › Consultation : un cabinet masque un champ ou une constante fournis par MedSol (sans toucher aux autres)
CREATE TABLE type_masque (
    cabinet_id  uuid   NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    genre       text   NOT NULL CHECK (genre IN ('CHAMP','CONSTANTE')),
    type_id     bigint NOT NULL,
    PRIMARY KEY (cabinet_id, genre, type_id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['fichier','redaction','message','message_lu','rdv_rappel','type_masque'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON %I USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet())', t || '_iso', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, DELETE ON fichier TO medsol_app;
GRANT SELECT, INSERT, UPDATE ON redaction, message_lu TO medsol_app;
GRANT SELECT, INSERT ON message, rdv_rappel TO medsol_app;
GRANT SELECT, INSERT, DELETE ON type_masque TO medsol_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO medsol_app;
