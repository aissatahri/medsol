# Publication de MedSol sur un serveur Hetzner, sans Docker :
#   powershell -ExecutionPolicy Bypass -File .\publier-medsol.ps1 -Serveur <adresse> -Domaine <nom>
# Production (serveur dédié) : .\publier-production.ps1, qui appelle ce script avec -Mode production.
# Le serveur de test medsol.appsol.app (sur appsol-1, le serveur du cabinet SGCM-Cardio) a été retiré le 27/11/2026 :
# serveur et domaine ne sont plus donnés par défaut, et appsol-1 est refusé (il ne doit plus héberger MedSol).
# Étapes : construction (avec les tests), envoi, installation ou mise à jour (deploiement/serveur/installer.sh),
# vérification en ligne. La première fois, l'installation ajoute PostgreSQL, le service, le site dans Caddy et les
# sauvegardes ; ensuite, seule l'application est remplacée.
param(
    [string]$Serveur = '',
    [string]$Utilisateur = 'aissa',
    [string]$Cle = (Join-Path $env:USERPROFILE '.ssh\id_ed25519_medsol'),
    [string]$Domaine = '',
    [int]$Port = 8081,
    [ValidateSet('test', 'production')] [string]$Mode = 'test',
    [string]$Admin = '',
    [switch]$SansTests
)
$ErrorActionPreference = 'Stop'
$racine = $PSScriptRoot
function Etape($texte) { Write-Host ''; Write-Host "== $texte" -ForegroundColor Cyan }
if (-not $Serveur -or -not $Domaine) { throw 'Indiquez -Serveur et -Domaine (production : publier-production.ps1).' }
if ($Serveur -eq '167.233.96.197' -or $Domaine -eq 'medsol.appsol.app') {
    throw 'Refusé : 167.233.96.197 (appsol-1) est le serveur du cabinet SGCM-Cardio ; MedSol n''y est plus installé.'
}
if ($Mode -eq 'production' -and $Admin -notmatch '^[^@\s]+@[^@\s]+\.[^@\s]+$') { throw 'Production : indiquez l''e-mail de l''administrateur (-Admin).' }
if ($Mode -eq 'production' -and $SansTests) { throw 'Production : les tests sont obligatoires (retirez -SansTests).' }
if (-not (Test-Path $Cle)) { throw "Clé SSH introuvable : $Cle (paramètre -Cle)." }
$ssh = @('-i', $Cle, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=accept-new')

Etape 'Construction'
Push-Location $racine
try {
    if ($SansTests) { mvn -q -B clean package -DskipTests } else { mvn -q -B clean package }
    if ($LASTEXITCODE -ne 0) { throw 'La construction (ou un test) a échoué : rien n''a été envoyé.' }
} finally { Pop-Location }
$jar = Get-ChildItem (Join-Path $racine 'target') -Filter 'medsol-*.jar' | Where-Object Name -notlike '*.original' | Select-Object -First 1
Write-Host ("Application construite ({0:N1} Mo)." -f ($jar.Length / 1MB))

Etape 'Envoi sur le serveur'
& ssh @ssh "$Utilisateur@$Serveur" 'rm -rf /tmp/medsol-installation && mkdir -p /tmp/medsol-installation'
$fichiers = @($jar.FullName,
    (Join-Path $racine 'deploiement\serveur\installer.sh'),
    (Join-Path $racine 'docker\sauvegarde\sauvegarde.sh'),
    (Join-Path $racine 'docker\sauvegarde\tester-restauration.sh'),
    (Join-Path $racine 'docker\sauvegarde\restaurer.sh'))
# connexion parfois coupée en cours d'envoi : jusqu'à 4 essais, la connexion maintenue active
$envoye = $false
for ($essai = 1; $essai -le 4 -and -not $envoye; $essai++) {
    if ($essai -gt 1) { Write-Host "Envoi interrompu : nouvel essai ($essai/4) dans 10 s..."; Start-Sleep -Seconds 10 }
    & scp @ssh -o ServerAliveInterval=15 -o ServerAliveCountMax=8 @fichiers "${Utilisateur}@${Serveur}:/tmp/medsol-installation/"
    $envoye = ($LASTEXITCODE -eq 0)
}
if (-not $envoye) { throw 'Envoi impossible après 4 essais (connexion coupée) : relancez plus tard.' }
& ssh @ssh "$Utilisateur@$Serveur" "mv /tmp/medsol-installation/$($jar.Name) /tmp/medsol-installation/medsol.jar"

Etape 'Installation / mise à jour'
& ssh @ssh "$Utilisateur@$Serveur" "sudo bash /tmp/medsol-installation/installer.sh $Domaine $Port $Mode $Admin"
if ($LASTEXITCODE -ne 0) { throw 'L''installation a échoué (voir ci-dessus).' }

Etape 'Vérification en ligne (le premier démarrage peut prendre 1 à 2 minutes)'
$ok = $false
for ($i = 1; $i -le 60; $i++) {
    try {
        $r = Invoke-WebRequest "https://$Domaine/login" -UseBasicParsing -TimeoutSec 5
        if ($r.StatusCode -eq 200) { $ok = $true; break }
    } catch { }
    Start-Sleep -Seconds 3
}
Write-Host ''
if ($ok) {
    Write-Host "MedSol est en ligne : https://$Domaine" -ForegroundColor Green
} else {
    Write-Host "MedSol ne répond pas encore. Sur le serveur : sudo journalctl -u medsol -n 100" -ForegroundColor Red
    exit 1
}
