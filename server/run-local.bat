@echo off
rem Runs the server on this Windows computer, for testing on the home or clinic network.
rem Needs Java 21 and PostgreSQL (see README, "Testing on your own computer").
rem The values below are for testing only. A real server uses docker-compose.yml with its own secrets.
chcp 65001 >nul
cd /d "%~dp0"

if "%DATABASE_PASSWORD%"=="" set /p DATABASE_PASSWORD=PostgreSQL password (the one chosen when installing it): 

set PORT=8090
set DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/parkinson
set DATABASE_USER=postgres
set CREATE_DATABASE=true
set JWT_SECRET=local-test-only-local-test-only-local-test-only
set NATIONAL_ID_PEPPER=local-test-only-pepper-local-test-only-pepper
set ADMIN_MOBILE=09120000000
set ADMIN_PASSWORD=admin12345
set ADMIN_NAME=Admin

echo.
echo Panel on this computer: http://localhost:8090   (admin: 09120000000 / admin12345)
echo For the phone, use this computer's address on the Wi-Fi, e.g. http://192.168.1.5:8090
ipconfig | findstr /i "IPv4"
echo.
call gradlew.bat run --console=plain
pause
