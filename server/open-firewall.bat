@echo off
rem Opens the ports the test server needs in Windows Firewall: TCP 80 and 443 for run-https.bat (Caddy), and
rem 3478 (UDP and TCP) and UDP 49160-49200 for voice and video calls. Nothing else is changed.
rem Right-click this file and choose "Run as administrator".
net session >nul 2>&1
if errorlevel 1 (
    echo Please right-click open-firewall.bat and choose "Run as administrator".
    pause
    exit /b 1
)
netsh advfirewall firewall delete rule name="Parkinson server HTTPS (Caddy)" >nul 2>&1
netsh advfirewall firewall add rule name="Parkinson server HTTPS (Caddy)" dir=in action=allow protocol=TCP localport=80,443
netsh advfirewall firewall delete rule name="Parkinson calls relay (TCP)" >nul 2>&1
netsh advfirewall firewall add rule name="Parkinson calls relay (TCP)" dir=in action=allow protocol=TCP localport=3478
netsh advfirewall firewall delete rule name="Parkinson calls relay (UDP)" >nul 2>&1
netsh advfirewall firewall add rule name="Parkinson calls relay (UDP)" dir=in action=allow protocol=UDP localport=3478,49160-49200
echo.
echo Done. Open: TCP 80, 443, 3478 and UDP 3478, 49160-49200. If the computer is a rented server, its provider's firewall may need the same.
pause
