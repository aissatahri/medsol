-- Comptes : langue, double authentification (TOTP), version de session (déconnexion immédiate), droits des
-- assistantes, jetons à usage unique (mot de passe oublié, invitation).

ALTER TABLE utilisateur
    ADD COLUMN langue          text    NOT NULL DEFAULT 'fr',
    ADD COLUMN totp_secret     text,                              -- clé base32 de l'application d'authentification
    ADD COLUMN totp_actif      boolean NOT NULL DEFAULT false,
    ADD COLUMN version_session int     NOT NULL DEFAULT 0,        -- +1 : toutes les sessions du compte sont fermées
    ADD COLUMN droits          text[]  NOT NULL DEFAULT ARRAY['CAISSE','RESULTATS']::text[];

-- Comptes existants : langue du cabinet (français par défaut)
UPDATE utilisateur u SET langue = c.langue FROM cabinet c WHERE c.id = u.cabinet_id AND c.langue = 'en';

-- Jetons à usage unique : seule l'empreinte SHA-256 est gardée (le lien envoyé n'est jamais stocké en clair).
-- Table de la plateforme : lue et écrite par les connexions du propriétaire (pages publiques, sans cabinet).
CREATE TABLE jeton (
    empreinte      text PRIMARY KEY,
    utilisateur_id uuid        NOT NULL REFERENCES utilisateur(id) ON DELETE CASCADE,
    type           text        NOT NULL CHECK (type IN ('REINITIALISATION','INVITATION')),
    expire_le      timestamptz NOT NULL,
    utilise_le     timestamptz,
    cree_le        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX jeton_utilisateur_idx ON jeton (utilisateur_id);
REVOKE ALL ON jeton FROM medsol_app;

-- La connexion lit aussi la version de session, la double authentification et les droits
DROP FUNCTION trouver_compte(text);
CREATE FUNCTION trouver_compte(p_email text)
    RETURNS TABLE (id uuid, cabinet_id uuid, email text, mot_de_passe text, nom_complet text, role text, actif boolean,
                   bloque_jusqua timestamptz, version_session int, totp_actif boolean, droits text[], langue text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$ SELECT u.id, u.cabinet_id, u.email, u.mot_de_passe, u.nom_complet, u.role, u.actif, u.bloque_jusqua,
          u.version_session, u.totp_actif, u.droits, u.langue
   FROM utilisateur u WHERE lower(u.email) = lower(p_email) $$;
REVOKE ALL ON FUNCTION trouver_compte(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION trouver_compte(text) TO medsol_app;
