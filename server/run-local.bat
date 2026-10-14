@echo off
rem Runs the server on this Windows computer, for testing on the home or clinic network.
rem Needs only Java 21 (Android Studio's own Java is used if none is installed).
rem The database is a built-in PostgreSQL kept in the local-data folder; nothing else to install.
rem The values below are for testing only. A real server uses docker-compose.yml with its own secrets.
chcp 65001 >nul
cd /d "%~dp0"

if "%JAVA_HOME%"=="" if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

set PORT=8090
set EMBEDDED_DB_DIR=local-data
set JWT_SECRET=local-test-only-local-test-only-local-test-only
set NATIONAL_ID_PEPPER=local-test-only-pepper-local-test-only-pepper
set ADMIN_MOBILE=09120000000
set ADMIN_PASSWORD=admin12345
set ADMIN_NAME=Admin
rem Calls: the built-in relay (TURN) needs this computer's public address; on a home network it stays off.
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "(Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.|10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' } | Select-Object -First 1).IPAddress"`) do set TURN_PUBLIC_IP=%%i
if defined TURN_PUBLIC_IP echo Calls relay on %TURN_PUBLIC_IP% (UDP/TCP 3478 and UDP 49160-49200 must be open: open-firewall.bat)

echo.
echo Panel on this computer: http://localhost:8090   (admin: 09120000000 / admin12345)
echo For the phone, use this computer's address on the Wi-Fi, e.g. http://192.168.1.5:8090
ipconfig | findstr /i "IPv4"
echo.
echo The first run downloads libraries and takes a few minutes. Keep this window open.
echo.
call gradlew.bat run --console=plain
pause
