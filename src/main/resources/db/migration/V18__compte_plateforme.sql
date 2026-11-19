-- Compte de l'administrateur de MedSol : son cabinet est « interne ». Il n'est ni un client (absent des listes, des
-- compteurs et des rappels de l'espace Plateforme), ni soumis à l'abonnement (jamais en lecture seule).
-- Il est repéré au démarrage et à la connexion grâce à MEDSOL_PLATFORM_ADMINS.
ALTER TABLE cabinet ADD COLUMN interne boolean NOT NULL DEFAULT false;
