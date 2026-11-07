$ErrorActionPreference = "Stop"

$jdk = "E:\orq-bolsalaboral\tools\jdk-25.0.5+8"
$raiz = "E:\orq-bolsalaboral\BolsaDeployManager"
$target = Join-Path $raiz "target"
$dist = Join-Path $target "dist"
$icono = Join-Path $raiz "resources\icono.ico"

# Staging input
$staging = Join-Path $target "jpackage-input"
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $staging "lib") -Force | Out-Null

Copy-Item (Join-Path $target "bolsadeploy-manager.jar") $staging -Force
Copy-Item (Join-Path $target "lib\*") (Join-Path $staging "lib") -Force

if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory -Path $dist -Force | Out-Null

Write-Host "Ejecutando jpackage para generar app-image portable..." -ForegroundColor Cyan

$jpackageArgs = @(
    "--type", "app-image",
    "--dest", $dist,
    "--name", "VPSPilot",
    "--app-version", "1.0.0",
    "--vendor", "VPSPilot",
    "--description", "VPSPilot - Orquestador de despliegues para VPS Ubuntu",
    "--input", $staging,
    "--main-jar", "bolsadeploy-manager.jar",
    "--main-class", "com.bolsadeploy.ui.Launcher",
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Xmx512m",
    "--icon", $icono
)

& "$jdk\bin\jpackage.exe" @jpackageArgs

if ($LASTEXITCODE -ne 0) {
    throw "jpackage fallo con codigo $LASTEXITCODE"
}

$exe = Join-Path $dist "VPSPilot\VPSPilot.exe"
if (Test-Path $exe) {
    Write-Host "`n¡EXITO TOTAL! Ejecutable generado en: $exe" -ForegroundColor Green
    Get-Item $exe | Select-Object Name, Length, LastWriteTime
} else {
    throw "No se encontro el ejecutable en $exe"
}
