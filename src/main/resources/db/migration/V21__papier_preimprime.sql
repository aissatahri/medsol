-- Pour le moment, les cabinets impriment sur leurs feuilles préimprimées : MedSol laisse vides l'en-tête et le pied
-- des documents (ordonnance, demandes d'analyses et d'imagerie, certificats). Le médecin peut décocher l'option dans
-- Paramètres › Cabinet pour que MedSol imprime l'en-tête.
ALTER TABLE cabinet ALTER COLUMN papier_entete SET DEFAULT true;
UPDATE cabinet SET papier_entete = true WHERE NOT papier_entete;
