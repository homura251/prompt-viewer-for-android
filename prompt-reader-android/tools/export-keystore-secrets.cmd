@echo off
setlocal

REM Runs the PowerShell helper with ExecutionPolicy bypass (no system changes).
REM Usage:
REM   - Double-click this file, or
REM   - Run from cmd:  tools\export-keystore-secrets.cmd

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0export-keystore-secrets.ps1" %*

endlocal
