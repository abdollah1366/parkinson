@echo off
rem HTTPS for the test server on a computer with a public IP (see README). Start run-local.bat first.
chcp 65001 >nul
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-https.ps1"
pause
