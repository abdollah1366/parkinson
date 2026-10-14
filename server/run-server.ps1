# Runs the server for real use on this Windows computer (see README, "Running for real use").
# Unlike run-local.bat, the secrets are random and made once for this computer, kept in server-secrets.env,
# patient data lives in server-data, and a backup is written to the backups folder every night at 03:00.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
[Console]::OutputEncoding = [Text.Encoding]::UTF8

if (-not $env:JAVA_HOME -and (Test-Path 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe')) {
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
}

function New-Secret {
    $bytes = New-Object byte[] 48
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

$secretsFile = Join-Path $PSScriptRoot 'server-secrets.env'
if (-not (Test-Path $secretsFile)) {
    @(
        '# Made once by run-server.ps1. Keep a copy somewhere safe (not on this computer only):'
        '# without NATIONAL_ID_PEPPER, the patients already on the server can no longer be matched.'
        "JWT_SECRET=$(New-Secret)"
        "NATIONAL_ID_PEPPER=$(New-Secret)"
        "TURN_SECRET=$(New-Secret)"
    ) | Set-Content -Path $secretsFile -Encoding ASCII
    Write-Host "New secrets written to server-secrets.env. Copy this file somewhere safe." -ForegroundColor Yellow
}
foreach ($line in Get-Content $secretsFile) {
    if ($line -match '^\s*([A-Z_]+)\s*=\s*(.+?)\s*$') { Set-Item -Path "env:$($Matches[1])" -Value $Matches[2] }
}

$env:PORT = '8090'
$env:EMBEDDED_DB_DIR = 'server-data'
$env:BACKUP_DIR = 'backups'
$env:TRUST_PROXY = 'true'

# The first start makes the administrator account. The password is used for this start only and not saved.
if (-not $env:RESTORE_FROM -and -not (Test-Path (Join-Path $PSScriptRoot 'server-data'))) {
    Write-Host 'First start: choose the administrator account of the panel.'
    $env:ADMIN_MOBILE = Read-Host 'Administrator mobile (e.g. 0912xxxxxxx)'
    do {
        $secure = Read-Host 'Administrator password (at least 8 characters)' -AsSecureString
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
    } while ($plain.Length -lt 8)
    $env:ADMIN_PASSWORD = $plain
    $env:ADMIN_NAME = 'Admin'
}

# To bring back a backup into a new server-data folder: set RESTORE_FROM to the zip before starting (see README).

$ip = Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.|10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' } | Select-Object -First 1
if ($ip) {
    $env:TURN_PUBLIC_IP = $ip.IPAddress
    Write-Host "Calls relay on $($ip.IPAddress) (open-firewall.bat opens its ports)"
}

Write-Host 'Building the server...'
& .\gradlew.bat installDist --console=plain -q
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }

Write-Host ''
Write-Host 'Server on port 8090. Start run-https.bat too, and use only the https:// address.'
Write-Host 'Backups: the backups folder, every night at 03:00. Copy them to another computer now and then.'
Write-Host 'Keep this window open.'
Write-Host ''
& .\build\install\parkinson-server\bin\parkinson-server.bat
