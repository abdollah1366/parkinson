@echo off
rem Opens TCP ports 80 and 443 in Windows Firewall for run-https.bat (Caddy). Nothing else is changed.
rem Right-click this file and choose "Run as administrator".
net session >nul 2>&1
if errorlevel 1 (
    echo Please right-click open-firewall.bat and choose "Run as administrator".
    pause
    exit /b 1
)
netsh advfirewall firewall add rule name="Parkinson server HTTPS (Caddy)" dir=in action=allow protocol=TCP localport=80,443
echo.
echo Done. Ports 80 and 443 are open. If the computer is a rented server, its provider's firewall may need the same.
pause
