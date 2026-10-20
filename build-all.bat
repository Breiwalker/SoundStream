@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-all.ps1"
if errorlevel 1 exit /b %errorlevel%
