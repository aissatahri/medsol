# MedSol — plan d'architecture (SaaS médical multi-spécialités)

Base de départ : `CabinetMedicalFinalCardio` (bureau JavaFX, MySQL) et `SGCM-Cardio-Web` (Spring Boot + Thymeleaf + htmx).
Cible : Afrique, Europe, USA. Un cabinet = un médecin (+ secrétaire/assistante). Base : `medsol_db`.

## 1. Choix technologiques

| Couche | Choix | Pourquoi |
|---|---|---|
| Backend | Java 21 + Spring Boot 4 (monolithe modulaire) | Réutilise le savoir-faire et le code du web actuel (sécurité, PDF iText, BCrypt) |
| Base | **PostgreSQL 16** (`medsol_db`) | Row-Level Security pour l'isolation des cabinets, JSONB pour les champs propres à chaque spécialité, fuseaux horaires, chiffrement |
| Migrations | Flyway | Schéma versionné, reproductible |
| Web | Thymeleaf + htmx (comme SGCM-Cardio-Web), PWA installable | Pages rapides, peu de JS, bon sur réseaux lents (Afrique) |
| Desktop | **Tauri 2** (ou Electron) qui encapsule l'app web + mode hors-ligne en phase 2 | Un seul code pour web et desktop ; l'ancien JavaFX n'est pas porté |
| Hébergement | Conteneurs (Docker) ; régions UE, US, Afrique (Maroc/Afrique du Sud) | Résidence des données par région |
| Paiement abonnement | Stripe (UE/US) + Paystack/Flutterwave/CMI (Afrique) | |
| Stockage fichiers | S3 compatible, chiffré, un préfixe par cabinet | |

## 2. Multi-tenant

- Tenant = `cabinet` (un médecin titulaire + utilisateurs : secrétaire, assistante).
- Une seule base `medsol_db`, colonne `cabinet_id` sur chaque table métier, **RLS PostgreSQL** (`current_setting('app.cabinet_id')`) en filet de sécurité en plus du filtrage applicatif.
- Compte « plateforme » (super-admin MedSol) séparé : cabinets, abonnements, support.

## 3. Multi-spécialités

Le point clé par rapport à l'existant (cardio codé en dur) :
- Table `specialite` + **modèles de spécialité** (templates) : champs cliniques, constantes, types d'analyses/imagerie, comptes rendus, certificats.
- Les champs spécifiques sont décrits en configuration (déjà amorcé : `champ_clinique`, `type_constante`) et stockés en JSONB.
- Packs livrés : médecine générale, cardiologie (migré de l'existant), pédiatrie, gynécologie, dermatologie, dentaire, ophtalmologie… Le médecin peut personnaliser.

## 4. International

- i18n : FR, EN, AR (RTL), puis ES/PT. Dates, nombres, fuseau par cabinet. Devise par cabinet.
- Référentiels par pays : médicaments (catalogue pays + saisie libre), codes actes (CCAM/NGAP FR, CPT/ICD-10 US, nomenclature locale Afrique), mentions légales des ordonnances/certificats.
- Conformité : RGPD (UE), HIPAA (USA : chiffrement, journal d'accès, BAA), CNDP loi 09-08 (Maroc) et lois africaines équivalentes. Journal d'audit, export/suppression des données, consentement, chiffrement au repos et en transit, sauvegardes.
- Pas de facturation « assurance » USA en v1 (hors périmètre).

## 5. Modules (reprise de l'existant)

Patients · Salle d'attente · Rendez-vous (+ rappels SMS/email/WhatsApp) · Consultation · Ordonnances · Analyses/imagerie · Documents PDF · Actes & caisse · Fichiers · Statistiques · Utilisateurs/rôles · Paramètres cabinet.
Nouveaux SaaS : inscription cabinet (onboarding), abonnement/facturation, essai gratuit, plans, super-admin, journal d'audit, sauvegarde/export.

## 6. Données existantes

Nouveau schéma propre (`medsol_db`), pas de reprise des 25 tables à l'identique : renommage en anglais ou français cohérent, clés UUID/bigint, `cabinet_id`, horodatage `created_at/updated_at`, suppression logique. Outil de migration depuis `cabinetmedical` (MySQL) → `medsol_db` pour les cabinets existants.

## 7. Feuille de route

| Phase | Contenu |
|---|---|
| 0 | **Fait (14/11/2026)** Squelette Maven, Flyway, schéma de base (cabinet, utilisateur, patient), authentification, tenant + RLS, i18n FR/EN |
| 1 | **Fait (14/11/2026)** Patients (recherche, doublons, archivage, consentement), salle d'attente (un seul patient en consultation), rendez-vous (anti-chevauchement en base), journal d'audit, en-têtes de sécurité (CSP) — 14 tests |
| 2 | **Fait (14/11/2026)** Rôles (médecin / assistante), comptes assistante, consultation avec modèles par spécialité (8 spécialités), signature qui fige le dossier, ordonnances + PDF A5 — 21 tests. Reste : chiffrement des champs sensibles, 2FA, export/suppression RGPD, police arabe pour les PDF |
| 3 | Actes/caisse, analyses/imagerie, fichiers, statistiques |
| 4 | Onboarding SaaS, abonnements, super-admin, audit, conformité |
| 5 | Desktop Tauri, PWA, hors-ligne/synchronisation |
| 6 | Autres spécialités, AR/ES, passerelles de paiement régionales |

## 7 bis. Reprise fidèle de SGCM-Cardio Web (décidée le 15/11/2026)

Règle : chaque écran, document et comportement est repris de SGCM-Cardio Web (lui-même fidèle au bureau), puis
adapté au multi-cabinet (PostgreSQL + RLS), au multi-spécialité (paramétrage par spécialité) et aux langues.

| Élément | État |
|---|---|
| Feuille de style, mise en page (menu, barre du haut, recherche globale), app.js | repris |
| Dossier médical à onglets (Info, Consultation, Analyses, Imageries, Ordonnances, Rendez-vous), liste des consultations, une par jour, corbeille | repris |
| Constantes (simples, complexes, IMC calculé) et champs cliniques paramétrés par spécialité | repris (modèles fournis pour 8 spécialités) |
| Ordonnance : catalogue (6 936 médicaments du bureau, dédoublonnés), posologie/durée/remarque, modifier, reprendre la précédente, ordonnances types, alerte allergie, ajout au catalogue du cabinet | repris |
| Analyses et imagerie : catalogue (FR/EN), consigne, résultat, ajout au catalogue | repris |
| PDF : ordonnance A5 préimprimée, demandes d'analyse/imagerie, certificats (consultation, aptitude, chronique, arrêts) | repris |
| Antécédents, allergies (bandeau) | repris |
| Connexion, inscription | repris |
| Patients (liste, fiche à onglets, formulaire avec doublons, situation familiale, couverture par pays, traitement en cours) | repris |
| Salle d'attente (état à l'arrivée, filtres, statuts, remise en attente, retrait), tableau de bord (compteurs, liste d'attente, RDV du jour) | repris |
| Données de démonstration (MEDSOL_DEMO=true) : 3 cabinets MA / FR / SN, médecin + assistante, 40 patients chacun | fait |
| Planning (FullCalendar jour/semaine/mois/liste, tiroir du rendez-vous, statuts et transitions du bureau, journal, report par glisser-déposer, « enregistrer quand même », arrivée → salle d'attente), horaires du cabinet | repris |
| Rappels de rendez-vous (WhatsApp/SMS) | à reprendre |
| Actes (payé / offert, prix figé), caisse (jour, impayés, encaissement sous verrou, annulation tracée, totaux par mode), facture numérotée enregistrée, reçu, clôture de caisse, onglet Paiement du dossier, Paramètres › Actes, mentions légales, devise du cabinet | repris |
| Abonnement SaaS : formules et tarifs par devise paramétrables, essai 30 jours, lecture seule à l'échéance ou en suspension, page Abonnement du cabinet, espace administrateur MedSol (paiements manuels, essais, suspensions, tarifs), paiement en ligne Stripe (session + rappel signé, idempotent) | fait (15/11/2026) |
| Lot 1 : export Excel de la caisse (CSV point-virgule), corbeille des consultations (rétablissement), journal d'accès (connexions comprises) | fait |
| Lot 2 : fichiers joints (type vérifié, bac à sable), courbes de toutes les constantes de la spécialité, fiche de soins PDF | fait |
| Lot 3 : lettre de référence, comptes rendus par spécialité (ETT/ECG/Holter, échographies, fond d'œil), statistiques annuelles | fait |
| Lot 4 : messagerie médecin ↔ assistante, rappels WhatsApp/appel (numéro international), Paramètres › Consultation (masquer un modèle, ajouter ses champs) | fait |
| Envoi automatique de SMS/WhatsApp (prestataire : Twilio, WhatsApp Business API, Orange…) | à décider |
| Paiement en ligne africain (CMI, Wave, Orange Money, Paystack…) | après Stripe, selon les pays |
| Comptes : « Mon compte » (nom, langue, mot de passe avec l'ancien), mot de passe oublié (lien unique 30 min), invitation e-mail (72 h), double authentification TOTP (recommandée, ou obligatoire pour les médecins avec MEDSOL_REQUIRE_2FA_DOCTORS), fermeture immédiate des sessions (version_session), droits des assistantes (caisse, fichiers/résultats), historique par compte dans le journal | fait (V11) |
| Prêt pour la production (V12) : clé TOTP chiffrée AES-256-GCM (MEDSOL_ENCRYPTION_KEY), anti-rejeu des codes, 10 codes de secours, retrait de la 2FA d'un médecin par la Plateforme, limitation des tentatives par IP, export du dossier patient (ZIP : HTML lisible + JSON + fichiers), Dockerfile, docker-compose (PostgreSQL, Caddy HTTPS, sauvegardes chiffrées quotidiennes avec test de restauration), DEPLOIEMENT.md | fait (image non construite sur le poste de dev : Docker absent) |
| Alertes de prescription : allergies (directes, de famille, croisées), doublons de substance et de famille, 27 interactions majeures, âge (enfant, sujet âgé), poids de l'enfant ; aperçu avant l'ajout, trace au journal si contre-indication outrepassée (ReglesPrescription, à faire relire par un médecin) | fait |
| Calcul de dose mg/kg (posologies structurées par DCI) ; grossesse/allaitement ; insuffisance rénale (DFG) ; base officielle (Thériaque, BCB, Vidal) sous licence | plus tard |
| Langues : français vérifié (clés complètes, doublons users.created / consultation.open corrigés), espagnol ajouté (341 clés, libellés de référence en V13, choix dans le menu, le profil et la connexion) | fait |
| Écrans du cabinet entièrement traduits (fr/en/es, 942 clés) : dossier, consultation, ordonnance, alertes, examens, patients, accueil, salle d'attente, planning (calendrier compris), caisse, statistiques, réglages, abonnement, messagerie, export ; messages du code via le service Textes ; test EcransLanguesTest | fait |
| Reste en français : PDF (ordonnances, certificats, factures, reçus), titres des comptes rendus (Redactions), espace Plateforme (équipe MedSol), noms d'examens du catalogue importé | à faire |
| Menu Consultations : historique filtré (patient, clinique, médicaments, période), repris de SGCM | fait |
| Prise de rendez-vous en ligne (V14) : page publique /r/<lien> (créneaux libres tirés des horaires, durée, délai, horizon), demande avec lien d'annulation, validation par le cabinet (fiche existante ou créée, rendez-vous posé), e-mail et WhatsApp, pastille du menu, Paramètres › Réservation en ligne avec QR code ; limite par IP, champ piège, 3 demandes en attente par téléphone | fait |
| Version téléphone : barre du bas (5 écrans selon le rôle), menu complet en panneau (☰), listes patients et consultations en cartes, champs 16 px, cibles tactiles, messagerie plein écran, application installable (manifeste, icônes) ; vérifiée par captures 390×844 | fait |
| PDF conformes au Maroc, en français (V15) : en-tête du médecin imprimé par MedSol (nom, spécialité, n° d'Ordre, INPE, cabinet, adresse, tél.) sauf option « papier à en-tête », « Fait à <ville>, le … », âge du patient et poids de l'enfant, DCI sous le nom commercial, signature et cachet ; factures et reçus avec IF, ICE (15 chiffres vérifiés), TP, INPE et exonération de TVA (art. 91 CGI) ; test DocumentsMarocTest | fait |
| E-mails réels : Brevo (domaine appsol.app authentifié : code, DKIM, DMARC), expéditeur no-reply@appsol.app, réglage par deploiement/configurer-email.ps1 | fait (test) |
| Serveur de production (16/11/2026) : Hetzner CPX12 Falkenstein 159.69.253.97, https://med.appsol.app ; installer.sh en mode production (Java 21, PostgreSQL 16, Caddy, ufw, mises à jour automatiques, sans démonstration, 2FA obligatoire pour les médecins, sauvegarde avant chaque mise à jour) ; publier-production.ps1 ; première sauvegarde et restauration vérifiées | fait |
| V16 commercial : conditions générales acceptées à l'inscription (/conditions, projet à valider), éditeur MedSol (mentions, RIB) dans Plateforme, factures d'abonnement numérotées MS-AAAA-NNNN, rappels d'abonnement J-7/J-1/échu, rappels automatiques des rendez-vous par e-mail (Paramètres › Rappels ; SMS Twilio prêt mais désactivé) | fait |
| Sauvegardes hors serveur : Storage Box BX11 Helsinki (u721334), copie rsync chiffrée après chaque sauvegarde (configurer-storage-box.ps1) | fait (16/11/2026) |
| Production, reste : e-mails (configurer-email.ps1), clés de secours notées, compte administrateur et informations de l'éditeur, dossier CNDP et contrat de sous-traitance avant le premier vrai patient | à faire |
| V17-V18 : 22 spécialités (Dentaire retirée de l'inscription, 15 ajoutées avec champs et constantes) ; inscription refaite (2 étapes, pays → devise et fuseau, jauge du mot de passe, volet « ce que comprend l'essai ») ; administrateur de la plateforme = cabinet interne (hors clients, compteurs et rappels, abonnement illimité, menu Plateforme seul, 2FA obligatoire) ; icônes unifiées | fait |
| V21 : papier préimprimé par défaut (en-tête et pied laissés vides) ; V22 : Paramètres › Impression (6 documents : police, marges, et par élément affichage, taille, gras, position fixe en mm ; aperçu sur données fictives avec grille de calage) | fait |
| V23-V24 : couvertures par cabinet, restauration des dossiers archivés, bandeau « serveur de test », parrainage (1 mois offert aux deux au 1er paiement du filleul), e-mails d'essai J+1/J+3/J+7, offre fondateur (prix MAD à vie, places limitées) ; vidéo de présentation (marketing/MedSol-presentation.mp4, voix neuronale, sources dans marketing/video-source) | fait |
| Ensuite : rappels automatiques SMS/WhatsApp | prochain lot |
| Restauration d'un seul cabinet ; compteurs de tentatives partagés (Redis) si plusieurs serveurs | plus tard |
| Textes des écrans repris et des PDF en anglais | à faire (passe de traduction dédiée) |

## 8. Décisions à confirmer

1. PostgreSQL (recommandé) ou rester sur MySQL comme l'existant ?
2. Desktop : simple encapsulation du web (en ligne) ou **hors-ligne complet** avec synchronisation (nettement plus coûteux, mais utile en Afrique) ?
3. Langues de lancement et pays pilote ?
4. Hébergement visé (Aiven actuel, Hetzner, AWS…) ?
