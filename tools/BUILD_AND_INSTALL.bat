@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0.."
set "ROOT=%CD%"
title Vialumix 1.20.1 - Build + Install
echo.
echo ============================================================
echo VIALUMIX 1.20.1 - BUILD + INSTALL
echo ============================================================
echo [INFO] Racine du projet :
echo %ROOT%
echo.

echo [0/3] Recherche d'un JDK compatible...
set "JAVA_EXE="
set "JAVA_HOME="

call :try_java "%JAVA_HOME%" 
if defined JAVA_EXE goto JAVA_OK

for /f "delims=" %%J in ('where java.exe 2^>nul') do if not defined JAVA_EXE call :try_java "%%J"

if not defined JAVA_EXE if exist "%ProgramFiles%\Eclipse Adoptium" for /r "%ProgramFiles%\Eclipse Adoptium" %%J in (java.exe) do if not defined JAVA_EXE call :try_java "%%J"
if not defined JAVA_EXE if exist "%ProgramFiles%\Java" for /r "%ProgramFiles%\Java" %%J in (java.exe) do if not defined JAVA_EXE call :try_java "%%J"
if not defined JAVA_EXE if exist "%ProgramFiles%\Microsoft" for /r "%ProgramFiles%\Microsoft" %%J in (java.exe) do if not defined JAVA_EXE call :try_java "%%J"

if not defined JAVA_EXE goto NO_JAVA

:JAVA_OK
echo.
echo [OK] JDK selectionne :
"%JAVA_EXE%" -version
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo.

echo [1/3] Compilation du mod Vialumix...
if not exist "%ROOT%\gradlew.bat" (
    echo [ERREUR] gradlew.bat introuvable a la racine du projet.
    goto BUILD_FAIL
)
call "%ROOT%\gradlew.bat" --no-daemon clean build
set "BUILD_RC=%ERRORLEVEL%"
if not "%BUILD_RC%"=="0" goto BUILD_FAIL

set "MC=%ROOT%\.minecraft"
set "MODS=%MC%\mods"
set "VIAL=%MC%\vialumix"
if not exist "%MODS%" mkdir "%MODS%"
if not exist "%VIAL%" mkdir "%VIAL%"
if not exist "%MC%\shaderpacks" mkdir "%MC%\shaderpacks"

echo.
echo [2/3] Installation du JAR Vialumix...
set "FOUND_JAR="
for /f "delims=" %%F in ('dir /b /a:-d "%ROOT%\build\libs\vialumix-*.jar" 2^>nul') do (
    echo %%F | findstr /i /r /c:"-sources\.jar$" >nul
    if errorlevel 1 if not defined FOUND_JAR set "FOUND_JAR=%ROOT%\build\libs\%%F"
)
if not defined FOUND_JAR goto NO_JAR

del /Q "%MODS%\vialumix-*.jar" >nul 2>&1
copy /Y "%FOUND_JAR%" "%MODS%\" >nul
if errorlevel 1 goto COPY_FAIL
echo [OK] JAR installe : %FOUND_JAR%

echo.
echo [3/3] Installation de l'instance locale...
if exist "%ROOT%\instance\mods" (
    for %%M in ("%ROOT%\instance\mods\*.jar") do if exist "%%~fM" (
        copy /Y "%%~fM" "%MODS%\" >nul
        echo [OK] %%~nxM
    )
)
for %%D in (nvngx_dlss.dll nvngx_dlssd.dll nvngx_dlssg.dll) do (
    if exist "%ROOT%\instance\vialumix\%%D" (
        copy /Y "%ROOT%\instance\vialumix\%%D" "%VIAL%\" >nul
        echo [OK] %%D
    )
)

echo.
echo [RT] Verification du backend natif...
if exist "%VIAL%\vialumix.dll" (
    echo [OK] vialumix.dll deja present.
) else (
    echo [INFO] vialumix.dll absent : compilation native demandee.
    if not exist "%ROOT%\native\BUILD_NATIVE.bat" (
        echo [ERREUR] native\BUILD_NATIVE.bat introuvable.
    ) else (
        call "%ROOT%\native\BUILD_NATIVE.bat" /auto
        set "NATIVE_RC=!ERRORLEVEL!"
        if exist "%VIAL%\vialumix.dll" (
            echo [OK] vialumix.dll compile et installe.
        ) else (
            echo [ATTENTION] Le backend natif n'a pas ete produit. Code !NATIVE_RC!
            echo [INFO] Consulte le diagnostic Vulkan/CMake ci-dessus.
            echo [INFO] Tu peux aussi relancer manuellement :
            echo        "%ROOT%\BUILD_VIALUMIX_NATIVE.bat"
        )
    )
)

echo.
echo ============================================================
echo [OK] INSTALLATION TERMINEE
echo JAR  : %MODS%
echo RT   : %VIAL%\vialumix.dll
echo DLSS : %VIAL%
echo ============================================================
echo.
pause
exit /b 0

:try_java
set "CAND=%~1"
if not defined CAND exit /b 0
if not exist "%CAND%" exit /b 0
"%CAND%" -version 2>"%TEMP%\vialumix_java_version.txt"
findstr /r /c:"version \"17\." "%TEMP%\vialumix_java_version.txt" >nul 2>&1 2>&1
if errorlevel 1 exit /b 0
set "JAVA_EXE=%CAND%"
for %%J in ("%CAND%") do set "JAVA_HOME=%%~dpJ.."
exit /b 0

:NO_JAVA
echo.
echo [ERREUR] Aucun JDK Java 17 n'a ete trouve.
echo Java 17 est requis pour ce projet.
echo.
pause
exit /b 10

:BUILD_FAIL
echo.
echo [ECHEC] La compilation Gradle a echoue. Code %BUILD_RC%.
echo.
pause
exit /b %BUILD_RC%

:NO_JAR
echo.
echo [ERREUR] Aucun JAR Vialumix n'a ete produit.
echo.
pause
exit /b 20

:COPY_FAIL
echo.
echo [ERREUR] Impossible de copier le JAR vers %MODS%.
echo.
pause
exit /b 21
