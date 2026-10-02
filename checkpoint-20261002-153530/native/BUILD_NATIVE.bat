@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

set "NATIVE_ROOT=%CD%"
set "PROJECT_ROOT=%NATIVE_ROOT%\.."
set "MC=%PROJECT_ROOT%\.minecraft"
set "VIAL=%MC%\vialumix"

echo.
echo ============================================================
echo VIALUMIX - NATIVE VULKAN RT BACKEND
echo ============================================================
echo [INFO] Racine : %PROJECT_ROOT%
echo [INFO] Sortie : %VIAL%\vialumix.dll
echo.

if not exist "%VIAL%" mkdir "%VIAL%"

rem ============================================================
rem [1/6] CMAKE
rem ============================================================

echo [1/6] Recherche de CMake...

where cmake >nul 2>&1

if errorlevel 1 (
    echo [ERREUR] CMake est introuvable dans le PATH.
    echo [INFO] Installe CMake puis relance BUILD_VIALUMIX_NATIVE.bat
    goto FAIL
)

set "CMAKE_VERSION="
for /f "delims=" %%C in ('cmake --version 2^>nul') do (
    if not defined CMAKE_VERSION set "CMAKE_VERSION=%%C"
)

echo [OK] !CMAKE_VERSION!

rem ============================================================
rem [2/6] VULKAN SDK
rem ============================================================

echo.
echo [2/6] Recherche du Vulkan SDK...

set "VK="

if defined VULKAN_SDK (
    if exist "%VULKAN_SDK%\Include\vulkan\vulkan.h" (
        set "VK=%VULKAN_SDK%"
    )
)

if not defined VK if exist "C:\VulkanSDK" (
    for /f "delims=" %%S in ('dir /b /ad /o-n "C:\VulkanSDK" 2^>nul') do (
        if not defined VK (
            if exist "C:\VulkanSDK\%%S\Include\vulkan\vulkan.h" (
                set "VK=C:\VulkanSDK\%%S"
            )
        )
    )
)

if not defined VK if exist "%ProgramFiles%\VulkanSDK" (
    for /f "delims=" %%S in ('dir /b /ad /o-n "%ProgramFiles%\VulkanSDK" 2^>nul') do (
        if not defined VK (
            if exist "%ProgramFiles%\VulkanSDK\%%S\Include\vulkan\vulkan.h" (
                set "VK=%ProgramFiles%\VulkanSDK\%%S"
            )
        )
    )
)

if not defined VK if exist "%ProgramFiles(x86)%\VulkanSDK" (
    for /f "delims=" %%S in ('dir /b /ad /o-n "%ProgramFiles(x86)%\VulkanSDK" 2^>nul') do (
        if not defined VK (
            if exist "%ProgramFiles(x86)%\VulkanSDK\%%S\Include\vulkan\vulkan.h" (
                set "VK=%ProgramFiles(x86)%\VulkanSDK\%%S"
            )
        )
    )
)

if not defined VK (
    echo [ERREUR] Vulkan SDK introuvable.
    echo.
    echo Le SDK doit fournir :
    echo   Include\vulkan\vulkan.h
    echo   Lib\vulkan-1.lib
    goto FAIL
)

set "VULKAN_SDK=%VK%"

echo [OK] Vulkan SDK : %VULKAN_SDK%

rem ============================================================
rem [3/6] JAVA / JNI
rem ============================================================

echo.
echo [3/6] Recherche de Java/JNI...

set "JH="

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\include\jni.h" (
        set "JH=%JAVA_HOME%"
    )
)

if not defined JH if exist "%ProgramFiles%\Java" (
    for /d %%J in ("%ProgramFiles%\Java\*") do (
        if not defined JH (
            if exist "%%~fJ\include\jni.h" (
                set "JH=%%~fJ"
            )
        )
    )
)

if not defined JH if exist "%ProgramFiles%\Eclipse Adoptium" (
    for /d %%J in ("%ProgramFiles%\Eclipse Adoptium\*") do (
        if not defined JH (
            if exist "%%~fJ\include\jni.h" (
                set "JH=%%~fJ"
            )
        )
    )
)

if not defined JH (
    echo [ERREUR] Aucun JDK avec include\jni.h n'a ete trouve.
    goto FAIL
)

set "JAVA_HOME=%JH%"

echo [OK] JAVA_HOME : %JAVA_HOME%

rem ============================================================
rem [4/6] VISUAL STUDIO 2026 / MSVC X64
rem ============================================================

echo.
echo [4/6] Detection de Visual Studio 2026 / MSVC x64...

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"

if not exist "%VSWHERE%" (
    set "VSWHERE=%ProgramFiles%\Microsoft Visual Studio\Installer\vswhere.exe"
)

if not exist "%VSWHERE%" (
    echo [ERREUR] vswhere.exe est introuvable.
    echo [INFO] Visual Studio 2026 semble installe mais son installateur
    echo [INFO] n'a pas fourni vswhere.exe a l'emplacement attendu.
    goto FAIL
)

set "VSINSTALL="

for /f "usebackq delims=" %%V in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath 2^>nul`) do (
    if not defined VSINSTALL set "VSINSTALL=%%V"
)

if not defined VSINSTALL (
    echo [ERREUR] Installation Visual Studio avec les outils C++ x64 introuvable.
    echo [INFO] Verifie que "Desktop development with C++" est installe.
    goto FAIL
)

echo [OK] Visual Studio : !VSINSTALL!

set "VCVARS=!VSINSTALL!\VC\Auxiliary\Build\vcvarsall.bat"

if not exist "!VCVARS!" (
    echo [ERREUR] vcvarsall.bat introuvable :
    echo !VCVARS!
    goto FAIL
)

echo [INFO] Initialisation de l'environnement MSVC x64...

call "!VCVARS!" x64 >nul

if errorlevel 1 (
    echo [ERREUR] Impossible d'initialiser MSVC x64.
    goto FAIL
)

where cl >nul 2>&1

if errorlevel 1 (
    echo [ERREUR] cl.exe est introuvable apres initialisation MSVC.
    goto FAIL
)

set "CL_PATH="

for /f "delims=" %%C in ('where cl 2^>nul') do (
    if not defined CL_PATH set "CL_PATH=%%C"
)

echo [OK] Compilateur : !CL_PATH!

cl 2>&1 | findstr /C:"for x64" >nul

if errorlevel 1 (
    echo [ERREUR] Le compilateur detecte n'est pas configure en x64.
    echo [INFO] Vialumix exige un backend 64 bits.
    goto FAIL
)

echo [OK] MSVC configure pour x64.

rem ============================================================
rem [5/6] CONFIGURATION CMAKE
rem ============================================================

echo.
echo [5/6] Configuration CMake...

set "CMAKE_GEN=Visual Studio 18 2026"

if exist "%NATIVE_ROOT%\build" (
    rmdir /s /q "%NATIVE_ROOT%\build"
)

mkdir "%NATIVE_ROOT%\build"

cmake ^
    -G "!CMAKE_GEN!" ^
    -A x64 ^
    -S "%NATIVE_ROOT%" ^
    -B "%NATIVE_ROOT%\build" ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DJAVA_HOME="%JAVA_HOME%" ^
    -DVULKAN_SDK="%VULKAN_SDK%"

if errorlevel 1 (
    echo.
    echo [ERREUR] La configuration CMake a echoue.
    goto FAIL
)

rem ============================================================
rem [6/6] COMPILATION
rem ============================================================

echo.
echo [6/6] Compilation du backend natif...

cmake --build "%NATIVE_ROOT%\build" --config Release --parallel

if errorlevel 1 (
    echo.
    echo [ERREUR] La compilation du backend natif a echoue.
    goto FAIL
)

rem ============================================================
rem RECHERCHE DU DLL
rem ============================================================

echo.
echo [INFO] Recherche de vialumix.dll...

set "DLL="

if exist "%NATIVE_ROOT%\build\Release\vialumix.dll" (
    set "DLL=%NATIVE_ROOT%\build\Release\vialumix.dll"
)

if not defined DLL if exist "%NATIVE_ROOT%\build\vialumix.dll" (
    set "DLL=%NATIVE_ROOT%\build\vialumix.dll"
)

if not defined DLL (
    echo [ERREUR] La compilation s'est terminee sans produire vialumix.dll.
    goto FAIL
)

echo [OK] DLL compilee :
echo !DLL!

rem ============================================================
rem SUPPRESSION DE L'ANCIEN DLL
rem ============================================================

echo.
echo [INFO] Suppression de l'ancien vialumix.dll...

if exist "%VIAL%\vialumix.dll" (
    del /f /q "%VIAL%\vialumix.dll"
)

rem ============================================================
rem INSTALLATION
rem ============================================================

echo.
echo [INFO] Installation du nouveau vialumix.dll...

copy /Y "!DLL!" "%VIAL%\vialumix.dll" >nul

if errorlevel 1 (
    echo [ERREUR] Impossible d'installer vialumix.dll.
    goto FAIL
)

if not exist "%VIAL%\vialumix.dll" (
    echo [ERREUR] vialumix.dll n'existe pas apres la copie.
    goto FAIL
)

echo.
echo ============================================================
echo [OK] BACKEND NATIF VIALUMIX COMPILE
echo ============================================================
echo DLL :
echo %VIAL%\vialumix.dll
echo.
echo Visual Studio : 2026
echo MSVC           : x64
echo Vulkan SDK     : %VULKAN_SDK%
echo Java/JNI       : %JAVA_HOME%
echo ============================================================

if /I "%~1"=="/auto" exit /b 0

pause
exit /b 0


:FAIL

echo.
echo ============================================================
echo [ECHEC] Le backend natif Vulkan n'a pas ete compile.
echo [INFO] Aucun faux vialumix.dll n'a ete cree.
echo ============================================================

if /I "%~1"=="/auto" exit /b 1

pause
exit /b 1