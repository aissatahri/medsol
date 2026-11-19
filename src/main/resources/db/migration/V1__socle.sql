-- MedSol : socle multi-cabinets. Isolation par Row-Level Security.
-- L'application se connecte puis fait SET ROLE medsol_app (rôle sans droit de contournement) et
-- positionne app.cabinet_id ; chaque table métier n'expose que les lignes de ce cabinet.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'medsol_app') THEN
        CREATE ROLE medsol_app NOLOGIN;
    END IF;
    -- l'utilisateur de connexion doit pouvoir faire SET ROLE medsol_app
    IF NOT pg_has_role(current_user, 'medsol_app', 'MEMBER') THEN
        EXECUTE format('GRANT medsol_app TO %I', current_user);
    END IF;
END $$;

-- Cabinet courant (NULL si non positionné => aucune ligne visible)
CREATE FUNCTION current_cabinet() RETURNS uuid
    LANGUAGE sql STABLE AS
$$ SELECT nullif(current_setting('app.cabinet_id', true), '')::uuid $$;

-- Référentiel des spécialités (lecture seule, partagé)
CREATE TABLE specialite (
    code      text PRIMARY KEY,
    libelle   jsonb NOT NULL            -- {"fr": "...", "en": "...", "ar": "..."}
);
INSERT INTO specialite (code, libelle) VALUES
    ('GENERAL',    '{"fr":"Médecine générale","en":"General practice","ar":"الطب العام"}'),
    ('CARDIO',     '{"fr":"Cardiologie","en":"Cardiology","ar":"أمراض القلب"}'),
    ('PEDIATRIE',  '{"fr":"Pédiatrie","en":"Pediatrics","ar":"طب الأطفال"}'),
    ('GYNECO',     '{"fr":"Gynécologie","en":"Gynecology","ar":"أمراض النساء"}'),
    ('DERMATO',    '{"fr":"Dermatologie","en":"Dermatology","ar":"الأمراض الجلدية"}'),
    ('DENTAIRE',   '{"fr":"Dentaire","en":"Dentistry","ar":"طب الأسنان"}'),
    ('OPHTALMO',   '{"fr":"Ophtalmologie","en":"Ophthalmology","ar":"طب العيون"}'),
    ('AUTRE',      '{"fr":"Autre","en":"Other","ar":"أخرى"}');

-- Un cabinet = un médecin titulaire + son personnel
CREATE TABLE cabinet (
    id             uuid PRIMARY KEY,
    nom            text        NOT NULL,
    pays           char(2)     NOT NULL,                -- ISO 3166-1
    fuseau         text        NOT NULL DEFAULT 'UTC',
    langue         text        NOT NULL DEFAULT 'fr',
    devise         char(3)     NOT NULL DEFAULT 'EUR',  -- ISO 4217
    specialite     text        NOT NULL REFERENCES specialite(code),
    plan           text        NOT NULL DEFAULT 'ESSAI',
    essai_fin      date,
    cree_le        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE utilisateur (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id      uuid        NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    email           text        NOT NULL,
    mot_de_passe    text        NOT NULL,               -- BCrypt
    nom_complet     text        NOT NULL,
    role            text        NOT NULL CHECK (role IN ('MEDECIN','SECRETAIRE','ADMIN')),
    actif           boolean     NOT NULL DEFAULT true,
    echecs          int         NOT NULL DEFAULT 0,
    bloque_jusqua   timestamptz,
    cree_le         timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX utilisateur_email_uq ON utilisateur (lower(email));
CREATE INDEX utilisateur_cabinet_idx ON utilisateur (cabinet_id);

CREATE TABLE patient (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id      uuid        NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    nom             text        NOT NULL,
    prenom          text        NOT NULL,
    date_naissance  date,
    sexe            char(1)     CHECK (sexe IN ('M','F','X')),
    telephone       text,
    email           text,
    identifiant_national text,                          -- CIN, NIR, SSN... selon le pays
    adresse         text,
    donnees         jsonb       NOT NULL DEFAULT '{}',  -- champs propres à la spécialité / au pays
    archive         boolean     NOT NULL DEFAULT false,
    cree_le         timestamptz NOT NULL DEFAULT now(),
    modifie_le      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX patient_cabinet_nom_idx ON patient (cabinet_id, lower(nom), lower(prenom));

-- Journal d'accès aux données de santé (RGPD / HIPAA) : ajout seul
CREATE TABLE journal_audit (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cabinet_id      uuid        NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    utilisateur_id  uuid,
    action          text        NOT NULL,               -- LECTURE, CREATION, MODIFICATION, SUPPRESSION, CONNEXION...
    objet           text        NOT NULL,               -- ex. patient
    objet_id        text,
    ip              text,
    quand           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX journal_cabinet_quand_idx ON journal_audit (cabinet_id, quand DESC);

-- Row-Level Security
ALTER TABLE cabinet       ENABLE ROW LEVEL SECURITY; ALTER TABLE cabinet       FORCE ROW LEVEL SECURITY;
ALTER TABLE utilisateur   ENABLE ROW LEVEL SECURITY; ALTER TABLE utilisateur   FORCE ROW LEVEL SECURITY;
ALTER TABLE patient       ENABLE ROW LEVEL SECURITY; ALTER TABLE patient       FORCE ROW LEVEL SECURITY;
ALTER TABLE journal_audit ENABLE ROW LEVEL SECURITY; ALTER TABLE journal_audit FORCE ROW LEVEL SECURITY;

CREATE POLICY cabinet_iso     ON cabinet       USING (id = current_cabinet())         WITH CHECK (id = current_cabinet());
CREATE POLICY utilisateur_iso ON utilisateur   USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet());
CREATE POLICY patient_iso     ON patient       USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet());
CREATE POLICY journal_iso     ON journal_audit USING (cabinet_id = current_cabinet()) WITH CHECK (cabinet_id = current_cabinet());

-- Droits du rôle applicatif
GRANT USAGE ON SCHEMA public TO medsol_app;
GRANT SELECT ON specialite TO medsol_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON cabinet, utilisateur, patient TO medsol_app;
GRANT SELECT, INSERT ON journal_audit TO medsol_app;

-- Connexion : l'e-mail est connu avant le cabinet. Fonction étroite qui contourne la RLS pour
-- retrouver un seul compte par e-mail (propriétaire = rôle de migration).
CREATE FUNCTION trouver_compte(p_email text)
    RETURNS TABLE (id uuid, cabinet_id uuid, email text, mot_de_passe text, nom_complet text,
                   role text, actif boolean, bloque_jusqua timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$ SELECT u.id, u.cabinet_id, u.email, u.mot_de_passe, u.nom_complet, u.role, u.actif, u.bloque_jusqua
   FROM utilisateur u WHERE lower(u.email) = lower(p_email) $$;
REVOKE ALL ON FUNCTION trouver_compte(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION trouver_compte(text) TO medsol_app;

-- Les e-mails sont uniques sur toute la plateforme : vérification avant inscription
CREATE FUNCTION email_existe(p_email text) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$ SELECT EXISTS (SELECT 1 FROM utilisateur WHERE lower(email) = lower(p_email)) $$;
REVOKE ALL ON FUNCTION email_existe(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION email_existe(text) TO medsol_app;

-- Compteur d'échecs de connexion (contourne la RLS, ne touche qu'une ligne)
CREATE FUNCTION enregistrer_echec_connexion(p_email text, p_max int, p_minutes int) RETURNS void
    LANGUAGE sql SECURITY DEFINER SET search_path = public AS
$$ UPDATE utilisateur
   SET echecs = CASE WHEN echecs + 1 >= p_max THEN 0 ELSE echecs + 1 END,
       bloque_jusqua = CASE WHEN echecs + 1 >= p_max THEN now() + make_interval(mins => p_minutes) ELSE bloque_jusqua END
   WHERE lower(email) = lower(p_email) $$;
REVOKE ALL ON FUNCTION enregistrer_echec_connexion(text,int,int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION enregistrer_echec_connexion(text,int,int) TO medsol_app;

CREATE FUNCTION enregistrer_succes_connexion(p_email text) RETURNS void
    LANGUAGE sql SECURITY DEFINER SET search_path = public AS
$$ UPDATE utilisateur SET echecs = 0, bloque_jusqua = NULL WHERE lower(email) = lower(p_email) $$;
REVOKE ALL ON FUNCTION enregistrer_succes_connexion(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION enregistrer_succes_connexion(text) TO medsol_app;
