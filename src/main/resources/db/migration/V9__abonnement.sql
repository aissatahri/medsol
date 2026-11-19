-- SaaS : formules paramétrables, abonnement de chaque cabinet (essai, actif, expiré, suspendu), paiements
-- d'abonnement (manuels ou Stripe), espace administrateur de la plateforme.

-- L'application bascule toujours sur medsol_app (soumis à la RLS). Le propriétaire du schéma n'est plus soumis à la
-- RLS : il sert aux migrations, aux fonctions SECURITY DEFINER (connexion) et à l'espace administrateur MedSol.
-- (Avec FORCE, un propriétaire non super-utilisateur en production ne trouvait aucun compte à la connexion.)
DO $$
DECLARE t text;
BEGIN
    FOR t IN SELECT relname FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind = 'r' AND relforcerowsecurity LOOP
        EXECUTE format('ALTER TABLE %I NO FORCE ROW LEVEL SECURITY', t);
    END LOOP;
END $$;

-- Formules (prix d'exemple, à fixer dans l'espace administrateur)
CREATE TABLE formule (
    code         text PRIMARY KEY,
    nom          jsonb   NOT NULL,
    description  jsonb   NOT NULL DEFAULT '{}',
    actif        boolean NOT NULL DEFAULT true,
    ordre        int     NOT NULL DEFAULT 0
);
CREATE TABLE formule_prix (
    formule_code text          NOT NULL REFERENCES formule(code) ON DELETE CASCADE,
    devise       char(3)       NOT NULL,
    mensuel      numeric(12,2) NOT NULL CHECK (mensuel >= 0),
    annuel       numeric(12,2) NOT NULL CHECK (annuel >= 0),
    PRIMARY KEY (formule_code, devise)
);
INSERT INTO formule (code, nom, description, ordre) VALUES
    ('CABINET', '{"fr":"MedSol Cabinet","en":"MedSol Practice"}',
     '{"fr":"Toutes les fonctions pour un médecin et son assistante.","en":"Every feature for one doctor and their assistant."}', 1);
INSERT INTO formule_prix (formule_code, devise, mensuel, annuel) VALUES
    ('CABINET', 'MAD', 390, 3900), ('CABINET', 'XOF', 25000, 250000), ('CABINET', 'EUR', 39, 390),
    ('CABINET', 'USD', 49, 490), ('CABINET', 'DZD', 5500, 55000), ('CABINET', 'TND', 120, 1200);

CREATE TABLE abonnement (
    cabinet_id    uuid PRIMARY KEY REFERENCES cabinet(id) ON DELETE CASCADE,
    formule_code  text NOT NULL REFERENCES formule(code),
    statut        text NOT NULL CHECK (statut IN ('ESSAI','ACTIF','SUSPENDU')),
    fin           date NOT NULL,                   -- dernier jour couvert ; au-delà : lecture seule
    periode       text NOT NULL DEFAULT 'MENSUEL' CHECK (periode IN ('MENSUEL','ANNUEL')),
    modifie_le    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE paiement_abonnement (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cabinet_id  uuid          NOT NULL REFERENCES cabinet(id) ON DELETE CASCADE,
    montant     numeric(12,2) NOT NULL,
    devise      char(3)       NOT NULL,
    periode     text          NOT NULL CHECK (periode IN ('MENSUEL','ANNUEL')),
    mode        text          NOT NULL CHECK (mode IN ('MANUEL','STRIPE')),
    reference   text,                              -- n° de virement, reçu, session Stripe…
    du          date          NOT NULL,
    au          date          NOT NULL,
    cree_le     timestamptz   NOT NULL DEFAULT now(),
    cree_par    text
);
CREATE UNIQUE INDEX paiement_abonnement_ref_uq ON paiement_abonnement (mode, reference) WHERE reference IS NOT NULL;

-- Chaque cabinet existant reçoit un abonnement d'essai (fin de son essai, ou 30 jours)
INSERT INTO abonnement (cabinet_id, formule_code, statut, fin)
SELECT id, 'CABINET', 'ESSAI', coalesce(essai_fin, current_date + 30) FROM cabinet;

-- Un nouveau cabinet reçoit son essai de 30 jours (fonction du propriétaire : medsol_app n'écrit pas les abonnements)
CREATE FUNCTION abonnement_essai() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    INSERT INTO abonnement (cabinet_id, formule_code, statut, fin) VALUES (NEW.id, 'CABINET', 'ESSAI', current_date + 30);
    RETURN NEW;
END $$;
CREATE TRIGGER cabinet_abonnement_essai AFTER INSERT ON cabinet FOR EACH ROW EXECUTE FUNCTION abonnement_essai();

-- Le cabinet lit son abonnement et ses paiements ; il ne les modifie pas (administrateur, Stripe)
ALTER TABLE abonnement          ENABLE ROW LEVEL SECURITY;
ALTER TABLE paiement_abonnement ENABLE ROW LEVEL SECURITY;
CREATE POLICY abonnement_iso ON abonnement          USING (cabinet_id = current_cabinet());
CREATE POLICY paiement_abo_iso ON paiement_abonnement USING (cabinet_id = current_cabinet());
GRANT SELECT ON abonnement, paiement_abonnement, formule, formule_prix TO medsol_app;
