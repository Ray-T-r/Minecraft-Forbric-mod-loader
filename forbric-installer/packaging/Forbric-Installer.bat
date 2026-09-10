@echo off
setlocal enabledelayedexpansion
rem Double-click launcher for Windows.
rem
rem Two things this has to survive, both of which look identical to the user -- a black window that flashes
rem and vanishes -- and neither of which is the installer's fault:
rem
rem   1. No Java on PATH. A Minecraft player often has no system-wide JDK at all; the only runtime on the
rem      machine is the one their launcher downloaded under .minecraft\runtime. So look there too.
rem   2. A .jar file association that is missing -jar. Windows' "always open with" dialog produces
rem      `java.exe "%1"`, which makes Java read the jar path as a CLASS NAME, print ClassNotFoundException
rem      and exit before anyone can read it. Launching through this script bypasses the association entirely.
rem
rem Nothing here exits without either starting the installer or leaving a message on screen.

set "JAR="
for %%J in ("%~dp0forbric-installer*.jar") do set "JAR=%%~fJ"
if not defined JAR (
  echo Could not find forbric-installer*.jar next to this script.
  echo Keep the two files in the same folder.
  echo.
  pause
  exit /b 1
)

rem --- Find a Java runtime, most-specific first -----------------------------------------------------------
set "JAVAW="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVAW=%JAVA_HOME%\bin\javaw.exe"

if not defined JAVAW (
  for /f "delims=" %%P in ('where javaw 2^>nul') do (
    if not defined JAVAW set "JAVAW=%%P"
  )
)

rem Minecraft launcher runtimes. Mojang has shipped both a flat <runtime>\bin and a nested
rem <runtime>\<os-arch>\<runtime>\bin layout, so recurse rather than guessing the depth.
if not defined JAVAW (
  for %%D in ("%APPDATA%\.minecraft\runtime" "%USERPROFILE%\.minecraft\runtime" "%~dp0..\runtime") do (
    if exist "%%~D" (
      for /f "delims=" %%P in ('dir /b /s "%%~D\javaw.exe" 2^>nul') do (
        if not defined JAVAW set "JAVAW=%%P"
      )
    )
  )
)

if not defined JAVAW (
  for /f "delims=" %%P in ('dir /b /s "%ProgramFiles%\Java\javaw.exe" "%ProgramFiles%\Eclipse Adoptium\javaw.exe" 2^>nul') do (
    if not defined JAVAW set "JAVAW=%%P"
  )
)

if not defined JAVAW (
  echo No Java runtime found.
  echo.
  echo Looked in: JAVA_HOME, PATH, %APPDATA%\.minecraft\runtime, and Program Files.
  echo.
  echo If you have a Minecraft launcher installed, start the game once so it downloads a runtime,
  echo then run this script again. Otherwise install Java 17 or newer.
  echo.
  pause
  exit /b 1
)

echo Using Java: %JAVAW%
echo Installer : %JAR%
echo.

rem javaw keeps the GUI free of a console window. It also throws stderr away, so if it fails we re-run on
rem java.exe purely to put the real error on screen -- otherwise the user gets the same silent flash this
rem script exists to prevent.
start /wait "" "%JAVAW%" -jar "%JAR%" %*
if errorlevel 1 (
  echo.
  echo The installer exited with an error. Re-running to show what it was:
  echo.
  set "JAVA_CONSOLE=%JAVAW:javaw.exe=java.exe%"
  if exist "!JAVA_CONSOLE!" (
    "!JAVA_CONSOLE!" -jar "%JAR%" %*
  )
  echo.
  pause
  exit /b 1
)
