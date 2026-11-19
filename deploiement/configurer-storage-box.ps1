# Branche une Hetzner Storage Box : chaque sauvegarde chiffrée de la nuit est copiée hors du serveur (rsync par SSH,
# port 23). À lancer depuis ce PC, une fois la Storage Box créée avec « SSH support » activé :
#   powershell -ExecutionPolicy Bypass -File .\deploiement\configurer-storage-box.ps1 -Box u123456
# Le mot de passe de la Storage Box est demandé UNE fois par ssh (pour y déposer la clé du serveur) ; il ne quitte
# pas votre PC et n'est enregistré nulle part.
param(
    [Parameter(Mandatory)] [string]$Box,                      # « Username » de la Storage Box, ex. u123456
    [string]$Hote = '',                                       # « Server » si différent de <Username>.your-storagebox.de
    [string]$Serveur = '159.69.253.97',
    [string]$Utilisateur = 'root',
    [string]$Cle = (Join-Path $env:USERPROFILE '.ssh\id_ed25519_medsol')
)
$ErrorActionPreference = 'Stop'
if ($Box -notmatch '^[a-z0-9][a-z0-9-]{2,40}$') { throw "Nom d'utilisateur de Storage Box inattendu : $Box (ex. u123456)" }
$hote = if ($Hote) { $Hote.Trim() } else { "$Box.your-storagebox.de" }
if ($hote -notmatch '^[a-z0-9.-]+$') { throw "Adresse de Storage Box inattendue : $hote" }
if (-not (Resolve-DnsName $hote -ErrorAction SilentlyContinue)) {
    throw "L'adresse $hote n'existe pas : dans la console Hetzner, ouvrez la Storage Box et recopiez « Username » (-Box) et « Server » (-Hote)."
}
$ssh = @('-i', $Cle, '-o', 'BatchMode=yes', "$Utilisateur@$Serveur")

function Distant([string]$script) {
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($script -replace "`r", '')))
    & ssh @ssh "echo $b64 | base64 -d > /tmp/medsol-box.sh && bash /tmp/medsol-box.sh; r=`$?; rm -f /tmp/medsol-box.sh; exit `$r"
    if ($LASTEXITCODE -ne 0) { throw "Échec sur le serveur (code $LASTEXITCODE)." }
}

Write-Host '== 1. Clé SSH du serveur pour la Storage Box' -ForegroundColor Cyan
Distant @'
set -e
command -v rsync >/dev/null || DEBIAN_FRONTEND=noninteractive apt-get install -y -q rsync >/dev/null
install -d -o postgres -g postgres -m 700 /var/lib/postgresql/.ssh
[ -f /var/lib/postgresql/.ssh/storagebox ] || sudo -u postgres ssh-keygen -q -t ed25519 -N '' -C medsol-sauvegarde -f /var/lib/postgresql/.ssh/storagebox
'@
$publique = (& ssh @ssh 'cat /var/lib/postgresql/.ssh/storagebox.pub').Trim()
if ($publique -notmatch '^ssh-ed25519 ') { throw 'Clé publique du serveur introuvable.' }

Write-Host "== 2. Dépôt de la clé sur la Storage Box (tapez le mot de passe de $Box)" -ForegroundColor Cyan
$publique | & ssh -p 23 -o StrictHostKeyChecking=accept-new "$Box@$hote" install-ssh-key
if ($LASTEXITCODE -ne 0) { throw 'Dépôt de la clé refusé : vérifiez le mot de passe et que « SSH support » est activé sur la Storage Box.' }

Write-Host '== 3. Copie automatique après chaque sauvegarde, puis essai' -ForegroundColor Cyan
Distant @"
set -e
cat > /usr/local/lib/medsol-sauvegarde/copie-externe.sh <<'EOF'
#!/bin/sh
# Copie hors du serveur (Storage Box) : appelée par sauvegarde.sh pour chaque fichier ; la synchronisation complète
# est faite une fois, après l'empreinte (.sha256). Les fichiers sont déjà chiffrés. --delete : même rétention qu'ici.
set -eu
case "`${FICHIER:-}" in *.sha256) ;; *) exit 0 ;; esac
rsync -a --delete -e "ssh -p 23 -i /var/lib/postgresql/.ssh/storagebox -o BatchMode=yes -o StrictHostKeyChecking=accept-new" \
  "`${MEDSOL_BACKUP_PATH:-/var/backups/medsol}/" "`${MEDSOL_STORAGEBOX}:medsol/"
EOF
chmod 755 /usr/local/lib/medsol-sauvegarde/copie-externe.sh
sudo -u postgres ssh -p 23 -i /var/lib/postgresql/.ssh/storagebox -o BatchMode=yes -o StrictHostKeyChecking=accept-new $Box@$hote mkdir medsol 2>/dev/null || true
sed -i -E '/^(MEDSOL_STORAGEBOX|MEDSOL_BACKUP_COPY_CMD)=/d' /etc/medsol-sauvegarde.env
printf 'MEDSOL_STORAGEBOX=%s\nMEDSOL_BACKUP_COPY_CMD=/usr/local/lib/medsol-sauvegarde/copie-externe.sh\n' '$Box@$hote' >> /etc/medsol-sauvegarde.env
systemctl start medsol-sauvegarde.service
echo "Fichiers sur la Storage Box :"
sudo -u postgres ssh -p 23 -i /var/lib/postgresql/.ssh/storagebox -o BatchMode=yes $Box@$hote ls -la medsol | tail -4
"@
Write-Host ''
Write-Host "Sauvegardes copiées chaque nuit sur $hote (dossier medsol)." -ForegroundColor Green
Write-Host 'Conseil : activez aussi les « snapshots » automatiques de la Storage Box dans la console Hetzner.' -ForegroundColor Yellow
