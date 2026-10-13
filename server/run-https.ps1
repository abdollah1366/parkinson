# Puts HTTPS in front of the test server (run-local.bat, port 8090) on a computer with a public IP and no domain.
# Uses Caddy and the free name <ip-with-dashes>.sslip.io; Caddy gets a Let's Encrypt certificate by itself.
# Needs TCP ports 80 and 443 open to the internet (open-firewall.bat) and nothing else listening on them.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$caddy = Join-Path $PSScriptRoot 'tools\caddy.exe'
if (-not (Test-Path $caddy)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $caddy) | Out-Null
    Write-Host 'Downloading Caddy (about 40 MB), only the first time...'
    try {
        Invoke-WebRequest -UseBasicParsing -OutFile $caddy -Uri 'https://caddyserver.com/api/download?os=windows&arch=amd64'
    } catch {
        Write-Host 'caddyserver.com did not answer, trying GitHub...'
        $zip = Join-Path $env:TEMP 'caddy.zip'
        Invoke-WebRequest -UseBasicParsing -OutFile $zip -Uri 'https://github.com/caddyserver/caddy/releases/download/v2.10.2/caddy_2.10.2_windows_amd64.zip'
        Expand-Archive -Force -Path $zip -DestinationPath (Split-Path $caddy)
        Remove-Item $zip
    }
}

$ip = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.|10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' } |
    Select-Object -First 1 -ExpandProperty IPAddress
if (-not $ip) { $ip = Read-Host 'Public IP address of this computer (for example 82.115.17.7)' }
$site = ($ip -replace '\.', '-') + '.sslip.io'

foreach ($port in 80, 443) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "Port $port is already used by another program (for example Apache in XAMPP). Stop it first." -ForegroundColor Yellow
    }
}

Write-Host ''
Write-Host "Server address for the phone and the browser:  https://$site" -ForegroundColor Green
Write-Host 'Keep this window and the run-local.bat window open.'
Write-Host ''
& $caddy reverse-proxy --from $site --to 127.0.0.1:8090
