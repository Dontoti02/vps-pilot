<#
.SYNOPSIS
  BolsaDeploy Manager — empaquetado (FASE H).

.DESCRIPTION
  1. Compila y empaqueta con Maven (incluye pruebas, salvo -OmitirPruebas).
  2. Genera el icono si falta (scripts/gen-icono.ps1).
  3. jpackage → app-image (carpeta portátil con runtime incluido).
  4. Si hay WiX Toolset 3 en el PATH → instalador .exe (con icono, menú y
     acceso directo). Si no, se avisa EXPLÍCITAMENTE de lo que falta.
  5. Zip portátil de la app-image.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\build.ps1
  powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -OmitirPruebas
#>
[CmdletBinding()]
param(
    [switch]$OmitirPruebas,
    [string]$Jdk = "",
    [string]$Maven = ""
)

$ErrorActionPreference = "Stop"
$raiz = Split-Path -Parent $PSScriptRoot

# ---------------------------------------------------------------- Herramientas
if (-not $Jdk) {
    $candidato = Join-Path $raiz "..\tools\jdk-25.0.5+8"
    if (Test-Path $candidato) { $Jdk = (Resolve-Path $candidato).Path }
}
if (-not $Maven) {
    $candidato = Join-Path $raiz "..\tools\apache-maven-3.9.9"
    if (Test-Path $candidato) { $Maven = (Resolve-Path $candidato).Path }
}
if (-not $Jdk -or -not (Test-Path (Join-Path $Jdk "bin\jpackage.exe"))) {
    throw "No se encontró un JDK 25 con jpackage. Use -Jdk <ruta> (se buscó ..\tools\jdk-25.0.5+8)."
}
if (-not $Maven) {
    throw "No se encontró Maven. Use -Maven <ruta> (se buscó ..\tools\apache-maven-3.9.9)."
}

$env:JAVA_HOME = $Jdk
$env:Path = (Join-Path $Jdk "bin") + ";" + (Join-Path $Maven "bin") + ";" + $env:Path

Write-Host "== BolsaDeploy Manager: empaquetado ==" -ForegroundColor Cyan
Write-Host "JDK   : $Jdk"
Write-Host "Maven : $Maven"

# ---------------------------------------------------------------- Maven
$mavenArgs = @("-B", "clean", "package")
if ($OmitirPruebas) { $mavenArgs += "-DskipTests" }
Write-Host "`n[1/5] mvn $($mavenArgs -join ' ')" -ForegroundColor Cyan
& mvn @mavenArgs
if ($LASTEXITCODE -ne 0) { throw "Maven falló (exit $LASTEXITCODE). Corrija el código/las pruebas antes de empaquetar." }

# ---------------------------------------------------------------- Icono
$icono = Join-Path $raiz "resources\icono.ico"
if (-not (Test-Path $icono)) {
    Write-Host "`n[2/5] Generando icono…" -ForegroundColor Cyan
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "gen-icono.ps1") -Salida $icono
} else {
    Write-Host "`n[2/5] Icono ya presente: $icono" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- jpackage
$dist = Join-Path $raiz "target\dist"
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory -Path $dist | Out-Null

$jar = Join-Path $raiz "target\bolsadeploy-manager.jar"
if (-not (Test-Path $jar)) { throw "No existe $jar" }
if (-not (Test-Path (Join-Path $raiz "target\lib"))) { throw "Faltan las dependencias en target\lib (fase copy-dependencies)." }

$comun = @(
    "--type", "app-image",
    "--dest", $dist,
    "--name", "BolsaDeployManager",
    "--app-version", "1.0.0",
    "--vendor", "BolsaDeploy",
    "--description", "Orquestador de despliegues de bolsa laboral para VPS Ubuntu",
    "--input", (Join-Path $raiz "target"),
    "--main-jar", "bolsadeploy-manager.jar",
    "--main-class", "com.bolsadeploy.ui.Launcher",
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Xmx512m"
)
if (Test-Path $icono) { $comun += @("--icon", $icono) }

Write-Host "`n[3/5] jpackage → app-image (runtime embebido)" -ForegroundColor Cyan
& jpackage @comun
if ($LASTEXITCODE -ne 0) { throw "jpackage falló (exit $LASTEXITCODE)." }

$appImage = Join-Path $dist "BolsaDeployManager"
if (-not (Test-Path (Join-Path $appImage "BolsaDeployManager.exe"))) {
    throw "La app-image no contiene BolsaDeployManager.exe."
}
Write-Host "App-image lista: $appImage"

# ---------------------------------------------------------------- Instalador EXE
Write-Host "`n[4/5] Instalador .exe (WiX)" -ForegroundColor Cyan
$wix = Get-Command candle.exe -ErrorAction SilentlyContinue
if ($wix) {
    $exeArgs = $comun + @(
        "--type", "exe",
        "--dest", $dist,
        "--win-menu", "--win-menu-group", "BolsaDeploy",
        "--win-shortcut", "--win-dir-chooser", "--win-per-user-install",
        "--win-upgrade-uuid", "6f1f5c3a-9a5e-4b3c-8f4d-2b7a1e9c0d11"
    )
    # jpackage exe requiere su propio --type; se reconstruye la lista base
    $base = @($comun | Select-Object -Skip 2)   # quitar "--type app-image --dest"
    $exeArgs = @("--type", "exe", "--dest", $dist) + $base +
        @("--win-menu", "--win-menu-group", "BolsaDeploy",
          "--win-shortcut", "--win-dir-chooser",
          "--win-upgrade-uuid", "6f1f5c3a-9a5e-4b3c-8f4d-2b7a1e9c0d11")
    if (Test-Path $icono) { } # el icono ya está en $base
    & jpackage @exeArgs
    if ($LASTEXITCODE -eq 0) {
        Get-ChildItem $dist -Filter *.exe | ForEach-Object { Write-Host "Instalador: $($_.FullName)" }
    } else {
        Write-Warning "jpackage --type exe falló (¿WiX incompleto?). Sigue disponible la app-image y el zip."
    }
} else {
    Write-Warning @"
No se encontró WiX Toolset 3 (candle.exe) en el PATH: NO se generará instalador .exe.
Para generarlo:
  1. Instale WiX Toolset 3.14 (https://wixtoolset.org) o 'choco install wixtoolset'.
  2. Añada su carpeta 'bin' al PATH.
  3. Vuelva a ejecutar este script.
Mientras tanto, la app-image y el zip portátil sí son utilizables.
"@
}

# ---------------------------------------------------------------- Zip portátil
Write-Host "`n[5/5] Zip portátil" -ForegroundColor Cyan
$zip = Join-Path $dist "BolsaDeployManager-portatil.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $appImage -DestinationPath $zip -CompressionLevel Optimal
Write-Host "Portátil: $zip ($([math]::Round((Get-Item $zip).Length/1MB,1)) MB)"

# ---------------------------------------------------------------- Resumen
Write-Host "`n== Artefactos ==" -ForegroundColor Green
Get-ChildItem $dist | ForEach-Object { Write-Host " - $($_.Name)" }
Write-Host @"

Notas:
 * La app-image y el portátil incluyen un runtime Java 25 (no requieren Java instalado).
 * El .exe de instalación requiere WiX Toolset 3 (ver aviso anterior si no aparece).
 * Pruebe la aplicación en una máquina limpia antes de distribuirla.
"@ -ForegroundColor DarkGray
