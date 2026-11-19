#!/bin/bash
# Installation de MedSol SANS Docker sur un serveur Ubuntu qui sert déjà d'autres sites avec Caddy (ex. appsol.app).
# Rejouable : ce qui existe déjà (base, secrets, bloc Caddy) est conservé ; le .jar et le service sont mis à jour.
#
#   sudo bash installer.sh <domaine> [port local] [test|production] [e-mail administrateur]
#     test (défaut)  : serveur partagé avec d'autres sites, cabinets de démonstration
#     production     : serveur dédié neuf ; Caddy, pare-feu et mises à jour de sécurité installés ; aucune donnée
#                      de démonstration ; double authentification obligatoire pour les médecins
#   ex. : sudo bash installer.sh medsol.appsol.app 8081
#         sudo bash installer.sh app.medsol.ma 8081 production admin@exemple.ma
#
# Attend dans /tmp/medsol-installation : medsol.jar et les scripts de sauvegarde (envoyés par publier-medsol.ps1).
set -euo pipefail

DOMAINE=${1:?usage : installer.sh <domaine> [port] [test|production] [e-mail administrateur]}
PORT=${2:-8081}
MODE=${3:-test}
ADMIN=${4:-}
SRC=/tmp/medsol-installation
ENV=/etc/medsol.env
ENV_SAUV=/etc/medsol-sauvegarde.env
journal() { echo "== $*"; }

[ -f "$SRC/medsol.jar" ] || { echo "$SRC/medsol.jar manquant" >&2; exit 1; }
case "$MODE" in
  test) ;;
  production) [[ "$ADMIN" == *@*.* ]] || { echo "production : e-mail de l'administrateur requis (4e paramètre)" >&2; exit 1; } ;;
  *) echo "mode inconnu : $MODE (test ou production)" >&2; exit 1 ;;
esac

# Mémoire selon le serveur : petit serveur partagé (test) ou serveur dédié (production, 4 Go et plus)
RAM_MO=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
if [ "$MODE" = production ] && [ "$RAM_MO" -ge 3500 ]; then
  JAVA_MEM="-Xms256m -Xmx1200m -XX:+UseG1GC -XX:MaxMetaspaceSize=256m"
  PG_MEM="max_connections = 50
shared_buffers = 512MB
work_mem = 8MB
maintenance_work_mem = 128MB
effective_cache_size = 1536MB"
elif [ "$MODE" = production ] && [ "$RAM_MO" -ge 1700 ]; then
  # serveur dédié de 2 Go (ex. CPX12) : environ 700 Mo pour Java, 256 Mo pour PostgreSQL, le reste au système
  JAVA_MEM="-Xms192m -Xmx700m -XX:+UseSerialGC -XX:MaxMetaspaceSize=200m"
  PG_MEM="max_connections = 40
shared_buffers = 256MB
work_mem = 4MB
maintenance_work_mem = 64MB
effective_cache_size = 768MB"
else
  JAVA_MEM="-Xms96m -Xmx320m -XX:+UseSerialGC -XX:MaxMetaspaceSize=170m -Xss512k"
  PG_MEM="max_connections = 30
shared_buffers = 48MB
work_mem = 2MB
maintenance_work_mem = 32MB
effective_cache_size = 256MB"
fi

# 0. Production : serveur dédié neuf (pare-feu, mises à jour de sécurité automatiques, Java, Caddy)
if [ "$MODE" = production ]; then
  if ! command -v caddy >/dev/null || ! command -v java >/dev/null || ! command -v ufw >/dev/null; then
    journal "Paquets du serveur (Java 21, Caddy, pare-feu, mises à jour automatiques)"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -q
    apt-get install -y -q openjdk-21-jre-headless ufw unattended-upgrades debian-keyring debian-archive-keyring \
        apt-transport-https curl gnupg openssl
    if ! command -v caddy >/dev/null; then
      curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
      curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
      apt-get update -q
      apt-get install -y -q caddy
      # Caddyfile neuf : seulement MedSol (bloc ajouté plus bas)
      echo "# Caddy du serveur MedSol" > /etc/caddy/Caddyfile
    fi
    dpkg-reconfigure -f noninteractive unattended-upgrades
  fi
  # Pare-feu : SSH et web uniquement (PostgreSQL et l'application restent en local)
  ufw allow OpenSSH >/dev/null
  ufw allow 80/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
  ufw --force enable >/dev/null
fi

# 1. PostgreSQL, réglé pour un petit serveur, joignable seulement en local
if ! command -v pg_ctlcluster >/dev/null; then
  journal "Installation de PostgreSQL"
  DEBIAN_FRONTEND=noninteractive apt-get update -q
  DEBIAN_FRONTEND=noninteractive apt-get install -y -q postgresql openssl
fi
PGVER=$(ls /etc/postgresql | sort -n | tail -1)
CONF=/etc/postgresql/$PGVER/main/conf.d/medsol.conf
if [ ! -f "$CONF" ]; then
  journal "Réglage mémoire de PostgreSQL $PGVER"
  cat > "$CONF" <<EOF
# MedSol ($MODE, ${RAM_MO} Mo de mémoire) : écoute locale uniquement
listen_addresses = 'localhost'
$PG_MEM
EOF
  systemctl restart postgresql
fi

# 2. Compte système, secrets (créés une seule fois, jamais affichés) et base
id medsol >/dev/null 2>&1 || useradd --system --home-dir /var/lib/medsol --create-home --shell /usr/sbin/nologin medsol
if [ ! -f "$ENV" ]; then
  journal "Secrets et base medsol_db"
  umask 077
  DBPASS=$(openssl rand -hex 24)
  if [ "$MODE" = production ]; then
    REGLAGES="MEDSOL_DEMO=false
MEDSOL_PLATFORM_ADMINS=$ADMIN
MEDSOL_REQUIRE_2FA_DOCTORS=true"
  else
    REGLAGES="MEDSOL_DEMO=true
MEDSOL_DEMO_RANDOM_PASSWORDS=true
MEDSOL_PLATFORM_ADMINS=admin@demo.medsol"
  fi
  cat > "$ENV" <<EOF
SPRING_PROFILES_ACTIVE=prod
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=$PORT
MEDSOL_DB_URL=jdbc:postgresql://127.0.0.1:5432/medsol_db
MEDSOL_DB_USER=medsol
MEDSOL_DB_PASSWORD=$DBPASS
MEDSOL_ENCRYPTION_KEY=$(openssl rand -base64 32)
MEDSOL_BASE_URL=https://$DOMAINE
$REGLAGES
EOF
  chown root:medsol "$ENV"
  chmod 640 "$ENV"
  sudo -u postgres psql -v ON_ERROR_STOP=1 -q <<EOF
CREATE ROLE medsol LOGIN PASSWORD '$DBPASS';
CREATE DATABASE medsol_db OWNER medsol;
DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'medsol_app') THEN CREATE ROLE medsol_app NOLOGIN; END IF; END \$\$;
GRANT medsol_app TO medsol;
-- propriétaire du schéma : les migrations écrivent les catalogues communes (sans cabinet) ; l'application, elle,
-- bascule sur medsol_app (SET ROLE), toujours soumis à l'isolation des cabinets
ALTER ROLE medsol BYPASSRLS;
\c medsol_db
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;
EOF
  umask 022
fi

# Serveur de test : bandeau « données fictives » sur toutes les pages
if [ "$MODE" = test ] && ! grep -q '^MEDSOL_ENVIRONNEMENT=' "$ENV"; then
  echo 'MEDSOL_ENVIRONNEMENT=test' >> "$ENV"
fi

# 3. Application : .jar et service (mémoire limitée pour cohabiter avec les autres sites)
journal "Application"
# Production : sauvegarde (avec test de restauration) avant chaque mise à jour ; pas de mise à jour sans elle
if [ "$MODE" = production ] && [ -f /etc/systemd/system/medsol-sauvegarde.service ]; then
  journal "Sauvegarde avant la mise à jour"
  systemctl start medsol-sauvegarde.service || { echo "Sauvegarde en échec : mise à jour annulée (journalctl -u medsol-sauvegarde)" >&2; exit 1; }
fi
install -d -o root -g medsol -m 750 /opt/medsol
install -o root -g medsol -m 640 "$SRC/medsol.jar" /opt/medsol/medsol.jar
cat > /etc/systemd/system/medsol.service <<EOF
[Unit]
Description=MedSol
After=network.target postgresql.service
Requires=postgresql.service

[Service]
User=medsol
Group=medsol
EnvironmentFile=/etc/medsol.env
WorkingDirectory=/var/lib/medsol
ExecStart=/usr/bin/java $JAVA_MEM -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -jar /opt/medsol/medsol.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/medsol

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable medsol >/dev/null
systemctl restart medsol

# 4. Caddy : un bloc de plus pour le domaine de MedSol (les autres sites ne sont pas touchés)
if ! grep -q "^$DOMAINE {" /etc/caddy/Caddyfile; then
  journal "Caddy : ajout de $DOMAINE"
  cp /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.avant-medsol-$(date +%Y%m%d%H%M%S)"
  cat >> /etc/caddy/Caddyfile <<EOF

# MedSol (ajouté par deploiement/serveur/installer.sh)
$DOMAINE {
	encode zstd gzip
	header {
		Strict-Transport-Security "max-age=31536000; includeSubDomains"
		-Server
	}
	request_body {
		max_size 12MB
	}
	reverse_proxy 127.0.0.1:$PORT
}
EOF
  if ! caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1; then
    echo "Configuration Caddy invalide : retour à la précédente" >&2
    cp "$(ls -1t /etc/caddy/Caddyfile.avant-medsol-* | head -1)" /etc/caddy/Caddyfile
    exit 1
  fi
  systemctl reload caddy
fi

# 5. Sauvegardes chiffrées quotidiennes (02 h), avec test de restauration
journal "Sauvegardes"
B=/usr/local/lib/medsol-sauvegarde
install -d -o root -g postgres -m 750 "$B"
install -o root -g postgres -m 750 "$SRC"/sauvegarde.sh "$SRC"/tester-restauration.sh "$SRC"/restaurer.sh "$B"/
sed -i 's/\r$//' "$B"/*.sh
install -d -o postgres -g postgres -m 700 /var/backups/medsol
if [ ! -f "$ENV_SAUV" ]; then
  umask 077
  cat > "$ENV_SAUV" <<EOF
PGDATABASE=medsol_db
MEDSOL_BACKUP_PATH=/var/backups/medsol
MEDSOL_BACKUP_PASSPHRASE=$(openssl rand -base64 32)
MEDSOL_BACKUP_DAYS=30
MEDSOL_BACKUP_TEST=true
EOF
  chown root:postgres "$ENV_SAUV"
  chmod 640 "$ENV_SAUV"
  umask 022
fi
cat > /etc/systemd/system/medsol-sauvegarde.service <<'EOF'
[Unit]
Description=Sauvegarde chiffrée de MedSol (avec test de restauration)
After=postgresql.service

[Service]
Type=oneshot
User=postgres
EnvironmentFile=/etc/medsol-sauvegarde.env
ExecStart=/bin/bash /usr/local/lib/medsol-sauvegarde/sauvegarde.sh maintenant
EOF
cat > /etc/systemd/system/medsol-sauvegarde.timer <<'EOF'
[Unit]
Description=Sauvegarde quotidienne de MedSol

[Timer]
OnCalendar=*-*-* 02:00:00
Persistent=true
RandomizedDelaySec=10m

[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now medsol-sauvegarde.timer >/dev/null

rm -rf "$SRC"
journal "Terminé. Démarrage de MedSol : journalctl -u medsol -f"
