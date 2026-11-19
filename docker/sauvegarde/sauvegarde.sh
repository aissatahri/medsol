#!/bin/sh
# Sauvegarde de medsol_db : pg_dump (format personnalisé) chiffré en AES-256 (openssl, clé dérivée PBKDF2 de
# MEDSOL_BACKUP_PASSPHRASE), empreinte SHA-256, rotation après MEDSOL_BACKUP_DAYS jours, copie hors du serveur
# (MEDSOL_BACKUP_COPY_CMD, ex. « rclone copy »), test de restauration automatique (MEDSOL_BACKUP_TEST=true).
#
#   sauvegarde.sh boucle       une sauvegarde par jour à MEDSOL_BACKUP_HOUR (défaut 02 h)
#   sauvegarde.sh maintenant   une sauvegarde tout de suite (avant une mise à jour, par exemple)
set -eu
# pipefail : bash, ash ; ignoré si le shell ne le connaît pas (le test de restauration détecte alors l'échec)
(set -o pipefail) 2>/dev/null && set -o pipefail

DIR=${MEDSOL_BACKUP_PATH:-/sauvegardes}
HEURE=${MEDSOL_BACKUP_HOUR:-02}
JOURS=${MEDSOL_BACKUP_DAYS:-30}
: "${MEDSOL_BACKUP_PASSPHRASE:?MEDSOL_BACKUP_PASSPHRASE manquante}"

journal() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*"; }

sauvegarder() {
  f="$DIR/medsol-$(date +%Y%m%d-%H%M%S).dump.enc"
  journal "Sauvegarde vers $f"
  pg_dump -Fc -Z 6 | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass env:MEDSOL_BACKUP_PASSPHRASE -out "$f.partiel"
  mv "$f.partiel" "$f"
  (cd "$DIR" && sha256sum "$(basename "$f")" > "$(basename "$f").sha256")
  journal "Terminée : $(du -h "$f" | cut -f1)"
  find "$DIR" -name 'medsol-*.dump.enc*' -mtime +"$JOURS" -exec rm -f {} \; -exec echo "Supprimée (plus de $JOURS jours) : {}" \;
  if [ -n "${MEDSOL_BACKUP_COPY_CMD:-}" ]; then
    journal "Copie hors du serveur"
    for x in "$f" "$f.sha256"; do FICHIER="$x" sh -c "$MEDSOL_BACKUP_COPY_CMD"; done
  fi
  if [ "${MEDSOL_BACKUP_TEST:-true}" = "true" ]; then
    "$(dirname "$0")/tester-restauration.sh" "$f"
  fi
}

case "${1:-boucle}" in
  maintenant)
    sauvegarder
    ;;
  boucle)
    journal "Sauvegarde quotidienne à ${HEURE} h, conservation ${JOURS} jours, dossier $DIR"
    dernier=""
    while true; do
      jour=$(date +%Y%m%d)
      if [ "$(date +%H)" = "$HEURE" ] && [ "$dernier" != "$jour" ]; then
        if sauvegarder; then dernier=$jour; else journal "ÉCHEC DE LA SAUVEGARDE : nouvel essai dans 5 minutes"; fi
      fi
      sleep 300
    done
    ;;
  *)
    echo "usage : sauvegarde.sh [boucle|maintenant]" >&2
    exit 2
    ;;
esac
