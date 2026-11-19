#!/bin/sh
# Restauration complète de medsol_db depuis une sauvegarde chiffrée. REMPLACE toutes les données actuelles.
# Arrêter l'application d'abord : docker compose stop app
#
#   restaurer.sh /sauvegardes/medsol-AAAAMMJJ-HHMMSS.dump.enc --oui
set -eu
# pipefail : bash, ash ; ignoré si le shell ne le connaît pas (le test de restauration détecte alors l'échec)
(set -o pipefail) 2>/dev/null && set -o pipefail

f=${1:-}
[ -n "$f" ] && [ -f "$f" ] || { echo "usage : restaurer.sh <fichier.dump.enc> --oui" >&2; exit 2; }
[ "${2:-}" = "--oui" ] || { echo "Ajoutez --oui : toutes les données actuelles de $PGDATABASE seront remplacées." >&2; exit 2; }
: "${MEDSOL_BACKUP_PASSPHRASE:?MEDSOL_BACKUP_PASSPHRASE manquante}"

if [ -f "$f.sha256" ]; then
  (cd "$(dirname "$f")" && sha256sum -c "$(basename "$f").sha256") || { echo "EMPREINTE INCORRECTE : fichier altéré" >&2; exit 1; }
fi

echo "Restauration de $(basename "$f") dans $PGDATABASE"
# connexions restantes fermées, base recréée vide
psql -d postgres -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$PGDATABASE' AND pid <> pg_backend_pid()" >/dev/null
dropdb --if-exists "$PGDATABASE"
createdb "$PGDATABASE"
# rôle des cabinets (propre au serveur, absent des sauvegardes) : à recréer sur un serveur neuf
psql -d "$PGDATABASE" -v ON_ERROR_STOP=1 -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'medsol_app') THEN CREATE ROLE medsol_app NOLOGIN; END IF; END \$\$; GRANT medsol_app TO CURRENT_USER;" >/dev/null
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:MEDSOL_BACKUP_PASSPHRASE -in "$f" \
  | pg_restore --exit-on-error -d "$PGDATABASE"
echo "Restauration terminée. Relancer l'application : docker compose start app"
