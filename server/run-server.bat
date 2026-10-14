@echo off
rem Runs the server for real use (random secrets of its own, server-data folder, nightly backups). See README.
rem Do not run run-local.bat at the same time: both use port 8090.
chcp 65001 >nul
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-server.ps1"
pause
