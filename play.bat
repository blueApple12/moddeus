@echo off
rem Launch Minecraft 26.3 (dev build, JBR 25 hotswap) with Moddeus.
cd /d "%~dp0"
set "JAVA_HOME=%~dp0.tools\jbr"
rem Rejoins the most recently played world automatically (Minecraft quick play).
set "WORLD="
for /f "delims=" %%W in ('dir /b /ad /o-d "run\saves" 2^>nul') do if not defined WORLD set "WORLD=%%W"
if defined WORLD (
  call "%~dp0gradlew.bat" runClient --args="--quickPlaySingleplayer \"%WORLD%\""
) else (
  call "%~dp0gradlew.bat" runClient
)
