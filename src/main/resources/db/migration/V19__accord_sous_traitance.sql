-- Accord de traitement des données (sous-traitance, loi 09-08) accepté par le médecin titulaire : à l'inscription,
-- ou à sa connexion suivante pour un cabinet existant (et à chaque nouvelle version des documents).
ALTER TABLE cabinet
    ADD COLUMN dpa_version      text,
    ADD COLUMN dpa_acceptee_le  timestamptz,
    ADD COLUMN dpa_accepte_par  uuid;
