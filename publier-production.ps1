# Publication de MedSol sur le serveur de PRODUCTION (serveur Hetzner dédié, vrais cabinets) :
#   powershell -ExecutionPolicy Bypass -File .\publier-production.ps1
# Toujours après avoir essayé la version en local (le serveur de test medsol.appsol.app a été retiré le 27/11/2026).
# La première fois : installe Java, PostgreSQL, Caddy (HTTPS), le pare-feu, les mises à jour de sécurité et les
# sauvegardes ; sans cabinets de démonstration, double authentification obligatoire pour les médecins.
# Les fois suivantes : sauvegarde de la base, puis remplacement de l'application. Les tests sont obligatoires.

param([switch]$Confirme)   # -Confirme : publication déjà approuvée (pas de saisie de « OUI »)

# ── À remplir une fois le serveur et le domaine achetés ──────────────────────────────────────────────
$Serveur = '159.69.253.97'                  # Hetzner CPX12 « medsol-production », Falkenstein
$Domaine = 'med.appsol.app'                 # enregistrement A dans Cloudflare, sans proxy (nuage gris)
$Admin   = 'aissatahri81@gmail.com'         # compte d'administration de la plateforme
# ──────────────────────────────────────────────────────────────────────────────────────────────────

$ErrorActionPreference = 'Stop'
if ($Serveur -like 'ADRESSE*' -or $Domaine -like 'NOM-*' -or $Admin -like 'EMAIL-*') {
    throw 'Remplissez d''abord $Serveur, $Domaine et $Admin en haut de publier-production.ps1.'
}
$ip = (Resolve-DnsName $Domaine -Type A -ErrorAction SilentlyContinue | Where-Object Type -eq 'A' | Select-Object -First 1).IPAddress
if ($ip -ne $Serveur) {
    throw "Le domaine $Domaine pointe vers « $ip » et non vers $Serveur : corrigez le DNS (enregistrement A, sans proxy Cloudflare) et attendez quelques minutes."
}
Write-Host "Publication en PRODUCTION sur $Domaine ($Serveur)." -ForegroundColor Yellow
if (-not $Confirme -and (Read-Host 'Confirmez en tapant OUI') -cne 'OUI') { Write-Host 'Annulé.'; exit 1 }
& (Join-Path $PSScriptRoot 'publier-medsol.ps1') -Serveur $Serveur -Utilisateur root -Domaine $Domaine -Mode production -Admin $Admin
exit $LASTEXITCODE
