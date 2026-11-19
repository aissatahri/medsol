-- Exploitation commerciale : conditions générales acceptées, éditeur de MedSol (factures d'abonnement, instructions
-- de paiement), rappels de fin d'abonnement, rappels automatiques des rendez-vous.

-- Conditions générales acceptées à l'inscription (version et date, preuve en cas de litige)
ALTER TABLE cabinet
    ADD COLUMN cgu_version      text,
    ADD COLUMN cgu_acceptees_le timestamptz;

-- Réglages de l'éditeur de MedSol (raison sociale, ICE, IF, RIB…) : écrits par l'administrateur de la plateforme ;
-- lisibles par les cabinets (ce sont les mentions de leurs factures et les coordonnées de paiement)
CREATE TABLE plateforme_reglage (
    cle         text PRIMARY KEY,
    valeur      text NOT NULL,
    modifie_le  timestamptz NOT NULL DEFAULT now()
);
GRANT SELECT ON plateforme_reglage TO medsol_app;

-- Facture d'abonnement : numéro annuel sans trou (MS-2026-0001), attribué à l'enregistrement du paiement
CREATE TABLE plateforme_compteur (
    annee   int PRIMARY KEY,
    valeur  int NOT NULL
);
ALTER TABLE paiement_abonnement ADD COLUMN numero text UNIQUE;

-- Rappels de fin d'essai ou d'abonnement déjà envoyés (un seul par échéance et par nature)
CREATE TABLE abonnement_rappel (
    cabinet_id  uuid NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    fin         date NOT NULL,
    nature      text NOT NULL CHECK (nature IN ('J7','J1','ECHU')),
    envoye_le   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (cabinet_id, fin, nature)
);

-- Rappels automatiques des rendez-vous (e-mail, SMS) : réglage du cabinet ; envois notés dans rdv_rappel
ALTER TABLE cabinet
    ADD COLUMN rappel_auto_email boolean NOT NULL DEFAULT false,
    ADD COLUMN rappel_auto_sms   boolean NOT NULL DEFAULT false,
    ADD COLUMN rappel_heures     int     NOT NULL DEFAULT 24 CHECK (rappel_heures BETWEEN 2 AND 72);
ALTER TABLE rdv_rappel DROP CONSTRAINT rdv_rappel_canal_check;
ALTER TABLE rdv_rappel ADD CONSTRAINT rdv_rappel_canal_check
    CHECK (canal IN ('WHATSAPP','SMS','APPEL','EMAIL_AUTO','SMS_AUTO'));
-- un seul envoi automatique par rendez-vous et par canal
CREATE UNIQUE INDEX rdv_rappel_auto_uq ON rdv_rappel (rdv_id, canal) WHERE canal IN ('EMAIL_AUTO','SMS_AUTO');
