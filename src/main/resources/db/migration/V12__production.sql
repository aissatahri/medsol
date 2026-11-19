-- Prêt pour la production : codes de secours de la double authentification, anti-rejeu des codes à 6 chiffres.
-- La clé TOTP est désormais chiffrée (AES-256-GCM) par l'application : les clés en clair sont chiffrées au démarrage.

-- Dernier pas de 30 s accepté : un code déjà utilisé ne peut pas resservir (vol par-dessus l'épaule, hameçonnage).
ALTER TABLE utilisateur ADD COLUMN totp_dernier_pas bigint NOT NULL DEFAULT 0;

-- Codes de secours : 10 codes à usage unique, montrés une seule fois ; seule leur empreinte SHA-256 est gardée.
-- Table de la plateforme (comme jeton) : connexions du propriétaire uniquement, jamais medsol_app.
CREATE TABLE code_secours (
    utilisateur_id uuid NOT NULL REFERENCES utilisateur(id) ON DELETE CASCADE,
    empreinte      text NOT NULL,
    utilise_le     timestamptz,
    cree_le        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (utilisateur_id, empreinte)
);
REVOKE ALL ON code_secours FROM medsol_app;
