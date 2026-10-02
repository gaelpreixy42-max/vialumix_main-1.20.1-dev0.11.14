@echo off
setlocal EnableExtensions EnableDelayedExpansion

title Vialumix - Checkpoint automatique

echo.
echo ============================================================
echo              VIALUMIX - CHECKPOINT AUTOMATIQUE
echo ============================================================
echo.

set "ROOT=%~dp0"
set "NATIVE=%ROOT%native"
set "DLL_BUILD=%NATIVE%\build\Release\vialumix.dll"
set "DLL_MC=%ROOT%.minecraft\vialumix\vialumix.dll"

echo [1/7] Verification de la structure...
if not exist "%ROOT%src\main" (
    echo [ERREUR] src\main introuvable.
    goto FAIL
)

if not exist "%NATIVE%\CMakeLists.txt" (
    echo [ERREUR] native\CMakeLists.txt introuvable.
    goto FAIL
)

if not exist "%NATIVE%\src\vialumix_jni.cpp" (
    echo [ERREUR] vialumix_jni.cpp introuvable.
    goto FAIL
)

echo [OK] Structure du projet.
echo.

echo [2/7] Verification de CMake...
cmake --version >nul 2>&1
if errorlevel 1 (
    echo [ERREUR] CMake introuvable.
    goto FAIL
)
echo [OK] CMake disponible.
echo.

echo [3/7] Verification du Vulkan SDK...
if not defined VULKAN_SDK (
    echo [ERREUR] VULKAN_SDK n'est pas defini.
    goto FAIL
)

if not exist "%VULKAN_SDK%" (
    echo [ERREUR] Vulkan SDK introuvable : %VULKAN_SDK%
    goto FAIL
)

echo [OK] Vulkan SDK : %VULKAN_SDK%
echo.

echo [4/7] Compilation du backend natif...
call "%ROOT%BUILD_VIALUMIX_NATIVE.bat"
if errorlevel 1 (
    echo [ERREUR] Compilation native echouee.
    goto FAIL
)

if not exist "%DLL_BUILD%" (
    echo [ERREUR] DLL native absente :
    echo %DLL_BUILD%
    goto FAIL
)

echo [OK] DLL native compilee.
echo.

echo [5/7] Verification de la DLL Minecraft...
if not exist "%DLL_MC%" (
    echo [ERREUR] DLL absente du Minecraft :
    echo %DLL_MC%
    goto FAIL
)

for %%A in ("%DLL_BUILD%") do set "BUILD_SIZE=%%~zA"
for %%A in ("%DLL_MC%") do set "MC_SIZE=%%~zA"

echo [INFO] DLL build : !BUILD_SIZE! octets
echo [INFO] DLL Minecraft : !MC_SIZE! octets

if "!BUILD_SIZE!" NEQ "!MC_SIZE!" (
    echo [ERREUR] La DLL Minecraft ne correspond pas a la DLL compilee.
    goto FAIL
)

echo [OK] DLL Minecraft synchronisee.
echo.

echo [6/7] Compilation du mod Fabric...
cd /d "%ROOT%"

call "%ROOT%gradlew.bat" build
if errorlevel 1 (
    echo [ERREUR] Build Gradle echoue.
    goto FAIL
)

echo [OK] Build Fabric termine.
echo.

echo [7/7] Verification finale...
if not exist "%DLL_MC%" (
    echo [ERREUR] La DLL a disparu apres le build.
    goto FAIL
)

echo.
echo ============================================================
echo                    CHECKPOINT OK
echo ============================================================
echo.
echo Backend natif     : OK
echo DLL compilee      : OK
echo DLL Minecraft     : OK
echo Gradle            : OK
echo.
echo Vialumix est pret pour le test Minecraft.
echo ============================================================
echo.

exit /b 0

:FAIL
echo.
echo ============================================================
echo                    CHECKPOINT ECHEC
echo ============================================================
echo.
echo Une verification a echoue.
echo Corrige uniquement l'erreur indiquee ci-dessus.
echo Aucun test Minecraft n'est necessaire tant que ce checkpoint
echo n'est pas OK.
echo.
pause
exit /b 1