$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

Write-Host '=== VIALUMIX 1.20.1 - BUILD + INSTALL ==='

function Get-JavaMajor($exe) {
    $line = & $exe -version 2>&1 | Select-String 'version' | Select-Object -First 1
    if (-not $line) { return $null }
    if ($line.Line -match '"(\d+)(?:\.(\d+))?') {
        $m = [int]$Matches[1]
        if ($m -eq 1 -and $Matches[2]) { return [int]$Matches[2] }
        return $m
    }
    return $null
}

$javaExe = $null
$pathJava = Get-Command java -ErrorAction SilentlyContinue
if ($pathJava) {
    $major = Get-JavaMajor $pathJava.Source
    if ($major -and $major -le 24) { $javaExe = $pathJava.Source }
}

if (-not $javaExe) {
    $runtime = Join-Path $env:APPDATA '.minecraft\runtime'
    if (Test-Path $runtime) {
        Get-ChildItem $runtime -Recurse -Filter java.exe -ErrorAction SilentlyContinue | ForEach-Object {
            if (-not $javaExe) {
                $major = Get-JavaMajor $_.FullName
                if ($major -and $major -le 24) { $javaExe = $_.FullName }
            }
        }
    }
}

if (-not $javaExe) {
    $roots = @(
        "$env:ProgramFiles\Java\jdk-17*\bin\java.exe",
        "$env:ProgramFiles\Java\jdk-21*\bin\java.exe",
        "$env:ProgramFiles\Eclipse Adoptium\jdk-17*\bin\java.exe",
        "$env:ProgramFiles\Eclipse Adoptium\jdk-21*\bin\java.exe",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-17*\bin\java.exe",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-21*\bin\java.exe"
    )
    foreach ($pattern in $roots) {
        $candidate = Get-ChildItem $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($candidate -and -not $javaExe) { $javaExe = $candidate.FullName }
    }
}

if (-not $javaExe) {
    throw 'Aucun Java compatible trouve. Java 25 ne peut pas lancer Gradle 8.14.1. Installe un JDK 17 ou 21.'
}

$javaHome = Split-Path (Split-Path $javaExe -Parent) -Parent
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
Write-Host "Java utilise : $javaExe (Java $(Get-JavaMajor $javaExe))"

& .\gradlew.bat --no-daemon build
if ($LASTEXITCODE -ne 0) { throw 'La compilation Gradle a echoue.' }

$mc = Join-Path $env:APPDATA '.minecraft'
$mods = Join-Path $mc 'mods'
$vial = Join-Path $mc 'vialumix'
New-Item -ItemType Directory -Force $mods,$vial,(Join-Path $mc 'shaderpacks') | Out-Null

$jar = Get-ChildItem .\build\libs\vialumix-*.jar | Where-Object { $_.Name -notmatch '-sources\.jar$' } | Select-Object -First 1
if (-not $jar) { throw 'Aucun JAR Vialumix n''a ete produit.' }
Copy-Item $jar.FullName (Join-Path $mods $jar.Name) -Force

foreach ($dll in @('nvngx_dlss.dll','nvngx_dlssd.dll','nvngx_dlssg.dll')) {
  $src = Join-Path (Join-Path $PSScriptRoot '..\instance\vialumix') $dll
  if (Test-Path $src) { Copy-Item $src (Join-Path $vial $dll) -Force }
}

Write-Host "JAR installe : $($mods)\$($jar.Name)"
Write-Host "Runtime DLSS : $vial"
Write-Host 'Tu peux maintenant lancer ton profil Fabric 1.20.1.'
