@echo off
setlocal
cd /d "%~dp0"
echo ROOT=%CD%
echo.
echo Gradle wrapper: 
if exist "gradlew.bat" (echo [OK] gradlew.bat) else (echo [ERREUR] gradlew.bat absent)
echo.
echo Lancement du build...
call "tools\BUILD_AND_INSTALL.bat"
echo.
echo Code final: %ERRORLEVEL%
pause
