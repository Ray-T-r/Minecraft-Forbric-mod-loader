@echo off
rem Double-click launcher for Windows. %~dp0 is this script's folder (with trailing backslash). javaw avoids a
rem console window. Requires a Java runtime on PATH (PCL2/HMCL users already have one).
where javaw >nul 2>nul
if errorlevel 1 (
  echo Java was not found on PATH. Install Java ^(a Minecraft launcher's JRE works^) and try again.
  pause
  exit /b 1
)
start "" javaw -jar "%~dp0forbric-installer.jar" %*
