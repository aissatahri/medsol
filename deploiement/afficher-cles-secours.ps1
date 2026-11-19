# Affiche, dans VOTRE terminal uniquement, les deux secrets sans lesquels les données sont perdues si le serveur
# disparaît : la clé de chiffrement de MedSol et la phrase des sauvegardes. À recopier dans un gestionnaire de mots
# de passe (et sur papier dans un coffre), jamais dans un e-mail, une capture ou une conversation.
#   powershell -ExecutionPolicy Bypass -File .\deploiement\afficher-cles-secours.ps1 -Serveur <ip> [-Utilisateur root]
param(
    [Parameter(Mandatory)] [string]$Serveur,
    [string]$Utilisateur = 'root',
    [string]$Cle = (Join-Path $env:USERPROFILE '.ssh\id_ed25519_medsol')
)
$ErrorActionPreference = 'Stop'
Write-Host 'Ces valeurs donnent accès aux données des patients. Personne ne doit les voir.' -ForegroundColor Yellow
if ((Read-Host 'Afficher maintenant ? (OUI)') -cne 'OUI') { exit 1 }
& ssh -i $Cle -o BatchMode=yes "$Utilisateur@$Serveur" "sudo grep -h -E '^(MEDSOL_ENCRYPTION_KEY|MEDSOL_BACKUP_PASSPHRASE)=' /etc/medsol.env /etc/medsol-sauvegarde.env"
Write-Host ''
Write-Host 'Recopiez-les, puis fermez cette fenêtre (Clear-Host efface l''écran).' -ForegroundColor Yellow
