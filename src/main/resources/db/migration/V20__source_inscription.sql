-- « Comment avez-vous connu MedSol ? » : canal d'acquisition déclaré à l'inscription (espace Plateforme : quels canaux
-- apportent des cabinets, et lesquels deviennent clients).
ALTER TABLE cabinet
    ADD COLUMN source        text CHECK (source IS NULL OR source IN
                             ('CONFRERE','VISITE','DELEGUE','WHATSAPP','FACEBOOK','LINKEDIN','GOOGLE','EVENEMENT','AUTRE')),
    ADD COLUMN source_detail text;
