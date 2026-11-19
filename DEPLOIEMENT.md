# Déployer MedSol en production

Un serveur Linux (2 processeurs, 4 Go de mémoire au minimum) avec Docker, et un nom de domaine qui pointe vers lui.
Quatre services démarrent : la base PostgreSQL, l'application, le proxy HTTPS (Caddy) et les sauvegardes.

## 0. Petit serveur déjà occupé (sans Docker) : medsol.appsol.app

Sur le serveur appsol (1 Go, Caddy et SGCM-Cardio Web déjà en place), MedSol est installé sans Docker par
[deploiement/serveur/installer.sh](deploiement/serveur/installer.sh), envoyé et lancé par :

```powershell
powershell -ExecutionPolicy Bypass -File .\publier-medsol.ps1        # -SansTests pour aller plus vite
```

PostgreSQL local (mémoire réduite), service `medsol` (127.0.0.1:8081, 320 Mo de tas Java), bloc `medsol.appsol.app`
ajouté au Caddyfile, sauvegarde chiffrée chaque nuit (`medsol-sauvegarde.timer`, dans `/var/backups/medsol`) avec test
de restauration. Secrets dans `/etc/medsol.env` et `/etc/medsol-sauvegarde.env` (créés une fois sur le serveur).

```sh
sudo journalctl -u medsol -f                        # journal de l'application
sudo systemctl start medsol-sauvegarde              # sauvegarde immédiate (+ test de restauration)
sudo journalctl -u medsol-sauvegarde -n 20          # résultat des sauvegardes
```

Le compte propriétaire `medsol` a l'attribut `BYPASSRLS` : les migrations écrivent les catalogues communs à tous les
cabinets. L'application, elle, travaille toujours sous `medsol_app`, soumis à l'isolation des cabinets.

## 1. Première installation (Docker)

```sh
git clone <dépôt> medsol && cd medsol
cp .env.example .env
openssl rand -base64 32   # à lancer une fois par secret : MEDSOL_DB_PASSWORD, MEDSOL_ENCRYPTION_KEY, MEDSOL_BACKUP_PASSPHRASE
nano .env                 # domaine, secrets, serveur d'e-mails, administrateurs de la plateforme
docker compose up -d --build
docker compose logs -f app   # « Started MedsolApplication » : c'est prêt
```

Ouvrir `https://<domaine>/register` et créer le premier cabinet avec l'e-mail indiqué dans `MEDSOL_PLATFORM_ADMINS` :
ce compte voit le menu **Plateforme** (abonnements, tarifs, retrait de la double authentification d'un médecin).

**À garder hors du serveur, dans un coffre-fort de mots de passe :** `MEDSOL_ENCRYPTION_KEY` et `MEDSOL_BACKUP_PASSPHRASE`.
Sans la phrase, les sauvegardes sont illisibles ; sans la clé, toutes les doubles authentifications sont à réactiver.

## 2. Sauvegardes

- Une sauvegarde chiffrée (AES-256) par jour à `MEDSOL_BACKUP_HOUR`, dans `MEDSOL_BACKUP_DIR`, conservée `MEDSOL_BACKUP_DAYS` jours.
- Après chacune, un **test de restauration** automatique : déchiffrement, restauration dans une base jetable, contrôles.
- Copie hors du serveur : `MEDSOL_BACKUP_COPY_CMD` (rclone vers S3, Backblaze, OVH Object Storage...). Fortement conseillé :
  un serveur détruit emporte les sauvegardes restées dessus.

```sh
docker compose logs sauvegarde                                     # résultats (« Restauration OK »)
docker compose exec sauvegarde sauvegarde.sh maintenant            # sauvegarde immédiate
docker compose exec sauvegarde tester-restauration.sh              # tester la dernière
```

### Restaurer (perte de données, nouveau serveur)

```sh
docker compose stop app
docker compose exec sauvegarde ls -lt /sauvegardes
docker compose exec sauvegarde restaurer.sh /sauvegardes/medsol-AAAAMMJJ-HHMMSS.dump.enc --oui
docker compose start app
```

Sur un serveur neuf : même `.env` (mêmes `MEDSOL_ENCRYPTION_KEY` et `MEDSOL_BACKUP_PASSPHRASE`), copier le fichier de
sauvegarde dans `MEDSOL_BACKUP_DIR`, `docker compose up -d db sauvegarde`, puis la restauration ci-dessus.

## 3. Mises à jour

```sh
docker compose exec sauvegarde sauvegarde.sh maintenant   # toujours avant
git pull
docker compose up -d --build                              # le schéma de la base est mis à jour au démarrage (Flyway)
```

## 4. Sécurité en place

- HTTPS obligatoire (certificat automatique, HSTS) ; cookies de session `Secure`, `HttpOnly`, `SameSite=Lax`.
- L'application et la base ne sont pas joignables depuis Internet : seul le proxy l'est (ports 80 et 443).
- Limitation des tentatives par adresse IP (connexion, code, mot de passe oublié, liens reçus, inscription).
  Elle se fie à l'en-tête `X-Forwarded-For` posé par Caddy : ne jamais publier le port 8080 de l'application.
- Données de chaque cabinet isolées dans la base (RLS) ; clé de double authentification chiffrée (AES-256-GCM).

## 5. Limites connues

- Un seul serveur d'application : avec plusieurs, partager les compteurs de tentatives (Redis) et les sessions.
- Restauration d'un seul cabinet : pas encore outillée (la restauration remplace toute la base).
