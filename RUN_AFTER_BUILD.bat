@echo off
setlocal
cd /d "%~dp0"
title Vialumix 1.20.1 - Build
color 07
echo Lancement de Vialumix...
echo.
if not exist "tools\BUILD_AND_INSTALL.bat" (
    echo [ERREUR] tools\BUILD_AND_INSTALL.bat introuvable.
    echo Dossier : %CD%
    echo.
    pause
    exit /b 1
)
call "tools\BUILD_AND_INSTALL.bat"
echo.
echo ============================================================
echo Fin du programme. Code : %ERRORLEVEL%
echo ============================================================
echo.
pause
endlocal
