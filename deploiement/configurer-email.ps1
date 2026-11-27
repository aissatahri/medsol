# Configure l'envoi des e-mails de MedSol (Brevo SMTP) sur le serveur, depuis ce PC :
#   powershell -ExecutionPolicy Bypass -File .\deploiement\configurer-email.ps1
# L'identifiant et la clé SMTP sont demandés ici (la clé ne s'affiche pas), envoyés par SSH et écrits dans
# /etc/medsol.env ; ils n'apparaissent ni à l'écran ni dans l'historique. Les anciennes lignes MEDSOL_MAIL_*
# (y compris celles mal tapées « MEDSOL-MAIL-… ») sont remplacées, puis MedSol redémarre.
param(
    [string]$Serveur = '159.69.253.97',     # medsol-production (le serveur de test sur appsol-1 a été retiré)
    [string]$Utilisateur = 'root',
    [string]$Cle = (Join-Path $env:USERPROFILE '.ssh\id_ed25519_medsol'),
    [string]$Expediteur = 'MedSol <no-reply@appsol.app>'
)
$ErrorActionPreference = 'Stop'
if ($Serveur -eq '167.233.96.197') { throw 'Refusé : 167.233.96.197 (appsol-1) est le serveur du cabinet SGCM-Cardio ; MedSol n''y est plus installé.' }

$identifiant = (Read-Host 'Identifiant SMTP Brevo (ex. xxxxxxx@smtp-brevo.com)').Trim()
if ($identifiant -notmatch '^[^@\s]+@[^@\s]+$') { throw "Identifiant invalide : il doit contenir @ (page SMTP et API de Brevo)." }
$secret = Read-Host 'Clé SMTP Brevo (invisible)' -AsSecureString
$motDePasse = [Net.NetworkCredential]::new('', $secret).Password.Trim()
if ($motDePasse -notmatch '^[A-Za-z0-9_-]{20,}$') { throw 'Clé SMTP invalide (attendu : xsmtpsib-…).' }

# Script exécuté sur le serveur ; il lit l'identifiant puis la clé sur l'entrée standard.
$distant = @"
set -e
read -r U; read -r P
U=`$(printf %s "`$U" | tr -d '\r'); P=`$(printf %s "`$P" | tr -d '\r')
sudo sed -i -E '/^MEDSOL[-_]MAIL[-_]/d' /etc/medsol.env
sudo sed -i -e '`$a\' /etc/medsol.env
printf 'MEDSOL_MAIL_HOST=smtp-relay.brevo.com\nMEDSOL_MAIL_PORT=587\nMEDSOL_MAIL_USER=%s\nMEDSOL_MAIL_PASSWORD=%s\nMEDSOL_MAIL_FROM="$Expediteur"\n' "`$U" "`$P" | sudo tee -a /etc/medsol.env >/dev/null
sudo systemctl restart medsol
rm -f /tmp/medsol-email.sh
echo "Variables : `$(sudo grep -c '^MEDSOL_MAIL_' /etc/medsol.env) / 5"
"@ -replace "`r", ''
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($distant))

"$identifiant`n$motDePasse`n" | & ssh -i $Cle -o BatchMode=yes "$Utilisateur@$Serveur" "echo $b64 | base64 -d > /tmp/medsol-email.sh && bash /tmp/medsol-email.sh"
if ($LASTEXITCODE -ne 0) { throw "Échec sur le serveur (code $LASTEXITCODE)." }
Write-Host 'E-mails configurés. MedSol redémarre (environ 1 minute), puis testez « Mot de passe oublié ».' -ForegroundColor Green
