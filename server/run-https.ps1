# Puts HTTPS in front of the test server (run-local.bat, port 8090) on a computer with a public IP and no domain.
# Uses Caddy with a Let's Encrypt certificate for the IP address itself (short-lived, renewed by Caddy), so no name
# service is needed: sslip.io is blocked in Iran and traefik.me hits Let's Encrypt's shared weekly limit.
# Set HTTPS_NAME_SUFFIX (for example nip.io) to use a <ip-with-dashes>.<suffix> name instead.
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
if ($env:HTTPS_NAME_SUFFIX) {
    $site = ($ip -replace '\.', '-') + '.' + $env:HTTPS_NAME_SUFFIX
    $tls = ''
} else {
    $site = $ip
    # Let's Encrypt issues IP address certificates only with its short-lived profile.
    $tls = "    tls {`r`n        issuer acme {`r`n            profile shortlived`r`n        }`r`n    }`r`n"
}
$config = Join-Path $PSScriptRoot 'tools\Caddyfile'
Set-Content -Path $config -Encoding Ascii -Value "https://$site {`r`n$tls    reverse_proxy 127.0.0.1:8090`r`n}"

# A Caddy left over from an earlier run (for example an older address) holds ports 80 and 443; stop it first.
Get-Process -Name caddy -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 1

foreach ($port in 80, 443) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "Port $port is already used by another program (for example Apache in XAMPP). Stop it first." -ForegroundColor Yellow
    }
}

Write-Host ''
Write-Host "Server address for the phone and the browser:  https://$site" -ForegroundColor Green
Write-Host 'Keep this window and the run-local.bat window open.'
Write-Host ''
& $caddy run --config $config --adapter caddyfile
