#!/bin/sh
# Test de restauration : la sauvegarde (la plus récente par défaut) est vérifiée (SHA-256), déchiffrée et restaurée
# dans une base jetable, puis comparée à la base en service (version du schéma, nombre de cabinets et de patients).
# Une sauvegarde qui ne se restaure pas ne vaut rien : ce test tourne après chaque sauvegarde.
#
#   tester-restauration.sh [fichier.dump.enc]
set -eu
# pipefail : bash, ash ; ignoré si le shell ne le connaît pas (le test de restauration détecte alors l'échec)
(set -o pipefail) 2>/dev/null && set -o pipefail

DIR=${MEDSOL_BACKUP_PATH:-/sauvegardes}
f=${1:-$(ls -1t "$DIR"/medsol-*.dump.enc 2>/dev/null | head -n 1)}
[ -n "$f" ] && [ -f "$f" ] || { echo "Aucune sauvegarde à tester" >&2; exit 1; }
: "${MEDSOL_BACKUP_PASSPHRASE:?MEDSOL_BACKUP_PASSPHRASE manquante}"
TEST=medsol_test_restauration

journal() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*"; }
nettoyer() { dropdb --if-exists "$TEST" 2>/dev/null || true; }
trap nettoyer EXIT

journal "Test de restauration de $(basename "$f")"
if [ -f "$f.sha256" ]; then
  (cd "$(dirname "$f")" && sha256sum -c "$(basename "$f").sha256" >/dev/null) || { echo "EMPREINTE INCORRECTE : fichier altéré" >&2; exit 1; }
fi
nettoyer
createdb "$TEST"
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:MEDSOL_BACKUP_PASSPHRASE -in "$f" \
  | pg_restore --no-owner --exit-on-error -d "$TEST"

requete() { psql -d "$1" -tAc "$2"; }
for q in "SELECT max(version::int) FROM flyway_schema_history WHERE success" \
         "SELECT count(*) FROM cabinet" "SELECT count(*) FROM utilisateur"; do
  r=$(requete "$TEST" "$q")
  [ -n "$r" ] || { echo "RESTAURATION INCOMPLÈTE : $q" >&2; exit 1; }
  journal "  $q -> $r"
done
patients=$(requete "$TEST" "SELECT count(*) FROM patient")
journal "  patients : $patients (en service : $(requete "$PGDATABASE" "SELECT count(*) FROM patient"))"
journal "Restauration OK"
