# =============================================================================
# gen-base-sql.ps1 — Genera base_instalacion.sql desde el dump oficial del repo
#
# Origen : bolsalaboralv2-audit/db_blaboral.sql (copia auditada del repo)
# Salida : src/main/resources/db/base_instalacion.sql
#
# Transformaciones:
#   1. Elimina INSERT de tablas con datos de la institución de origen
#      (datos personales: personas, usuarios, CVs, ofertas, postulaciones...).
#   2. Resetea la marca en system_configuration (nombre/logo/favicon/banner).
#   3. Añade los 2 registros de migración que faltan en el dump
#      (0001_01_01_000001_create_cache_table / 0001_01_01_000002_create_jobs_table)
#      para que `php artisan migrate` no intente recrear tablas existentes.
#   4. Conserva intactos CREATE TABLE, claves, índices, AUTO_INCREMENT y FKs.
#
# Uso: powershell -ExecutionPolicy Bypass -File scripts\gen-base-sql.ps1
# =============================================================================
param(
    [string]$DumpPath = "$PSScriptRoot\..\..\bolsalaboralv2-audit\db_blaboral.sql",
    [string]$OutPath  = "$PSScriptRoot\..\src\main\resources\db\base_instalacion.sql"
)

$ErrorActionPreference = 'Stop'

# Tablas con datos de la institución de origen que NO se importan
$Excluir = @(
    'person',
    'user',
    'job_opportunity_company',
    'job_opportunity_offer',
    'job_opportunity_applications',
    'job_opportunity_user_cv',
    'job_opportunity_offer_state_detail',
    'saved_offers',
    'user_notifications',
    'password_reset_tokens',
    'sessions',
    'study_programs',
    'rol_user'
)

# --- 1. Lectura estricta UTF-8 (aborta si el dump no es UTF-8 válido) --------
$utf8Strict = New-Object System.Text.UTF8Encoding($false, $true)
$bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $DumpPath))
try { $dump = $utf8Strict.GetString($bytes) }
catch { throw "El dump no es UTF-8 valido: $DumpPath" }

# --- 2. Dividir en sentencias SQL respetando comillas/comentarios ------------
function Split-SqlStatements([string]$sql) {
    $stmts = New-Object System.Collections.Generic.List[string]
    $sb = New-Object System.Text.StringBuilder
    $inStr = $false; $inBacktick = $false; $inLineC = $false; $inBlockC = $false
    for ($i = 0; $i -lt $sql.Length; $i++) {
        $c = $sql[$i]; $n = if ($i + 1 -lt $sql.Length) { $sql[$i+1] } else { [char]0 }
        if ($inLineC) { [void]$sb.Append($c); if ($c -eq "`n") { $inLineC = $false }; continue }
        if ($inBlockC) { [void]$sb.Append($c); if ($c -eq '*' -and $n -eq '/') { [void]$sb.Append($n); $i++; $inBlockC = $false }; continue }
        if ($inStr) {
            [void]$sb.Append($c)
            if ($c -eq '\' -and $n -ne [char]0) { [void]$sb.Append($n); $i++; continue }
            if ($c -eq "'") { if ($n -eq "'") { [void]$sb.Append($n); $i++ } else { $inStr = $false } }
            continue
        }
        if ($inBacktick) { [void]$sb.Append($c); if ($c -eq '`') { $inBacktick = $false }; continue }
        if ($c -eq '-' -and $n -eq '-') { $inLineC = $true; [void]$sb.Append($c); continue }
        if ($c -eq '/' -and $n -eq '*') { $inBlockC = $true; [void]$sb.Append($c); continue }
        if ($c -eq "'") { $inStr = $true; [void]$sb.Append($c); continue }
        if ($c -eq '`') { $inBacktick = $true; [void]$sb.Append($c); continue }
        if ($c -eq ';') { [void]$sb.Append($c); $stmts.Add($sb.ToString()); [void]$sb.Clear(); continue }
        [void]$sb.Append($c)
    }
    $rest = $sb.ToString().Trim()
    if ($rest.Length -gt 0) { $stmts.Add($rest) }
    return $stmts
}

$statements = Split-SqlStatements $dump
Write-Host "Sentencias leidas: $($statements.Count)"

# --- 3. Filtrar INSERTs institucionales y parchear system_configuration ------
$kept = New-Object System.Collections.Generic.List[string]
$removed = 0
$patched = 0

foreach ($s in $statements) {
    # Quitar lineas de comentario previas a la sentencia p.ej. "-- Volcado de datos..."
    $t = ($s -replace '(?m)^\s*--.*$', '') -replace '^\s+', ''
    $isInsert = $false; $tbl = $null
    if ($t -match '^INSERT\s+INTO\s+`([^`]+)`') { $isInsert = $true; $tbl = $Matches[1] }

    if ($isInsert -and ($Excluir -contains $tbl)) { $removed++; continue }

    if ($isInsert -and $tbl -eq 'system_configuration') {
        $orig = $s
        $s = $s -replace "'IESP VILCANOTA'", "'BOLSALABORAL'"
        $s = $s -replace "'/uploads/(logo|favicon|banner)_[0-9]+\.[a-z]+'", "NULL"
        if ($s -ne $orig) { $patched++ }
    }
    $kept.Add($s)
}

# --- 3b. Reiniciar AUTO_INCREMENT de las tablas excluidas (restos del origen)
$autoReset = 0
for ($i = 0; $i -lt $kept.Count; $i++) {
    $m = [regex]::Match($kept[$i], 'ALTER TABLE `(\w+)`\s+MODIFY `id`[^;]*AUTO_INCREMENT=(\d+);')
    if ($m.Success -and ($Excluir -contains $m.Groups[1].Value)) {
        $kept[$i] = $kept[$i] -replace 'AUTO_INCREMENT=\d+', 'AUTO_INCREMENT=1'
        $autoReset++
    }
}
Write-Host "AUTO_INCREMENT reiniciados en tablas excluidas: $autoReset"
Write-Host "INSERT institucionales eliminados: $removed (esperados: $($Excluir.Count))"
Write-Host "system_configuration parcheada: $patched (esperado: 1)"
if ($removed -ne $Excluir.Count) { throw "Numero inesperado de INSERT eliminados: $removed" }
if ($patched -ne 1)            { throw "system_configuration no se parcheo correctamente" }

# --- 4. Añadir registros de migracion faltantes -----------------------------
$migAdd = @"
;
INSERT INTO ``migrations`` (``id``, ``migration``, ``batch``) VALUES (32, '0001_01_01_000001_create_cache_table', 99), (33, '0001_01_01_000002_create_jobs_table', 99)
"@
$kept.Add($migAdd)

# --- 5. Recuento de verificacion -------------------------------------------
$all = ($kept -join "`n")
$checks = @{
    'CREATE TABLE'     = ([regex]::Matches($all, 'CREATE TABLE')).Count
    'FOREIGN KEY'      = ([regex]::Matches($all, 'FOREIGN KEY')).Count
    'INSERT INTO'      = ([regex]::Matches($all, 'INSERT INTO')).Count
}
# INSERT esperados: 24 sentencias INSERT originales en el dump (23 tablas; una
# tabla tiene 2 sentencias, person, que el parser une en una sola) - 13
# institucionales + 1 de migraciones = 12
Write-Host ("Verificacion -> CREATE TABLE: {0} (esperado 30) | FOREIGN KEY: {1} (esperado 24) | INSERT INTO: {2} (esperado 12)" -f $checks['CREATE TABLE'], $checks['FOREIGN KEY'], $checks['INSERT INTO'])
if ($checks['CREATE TABLE'] -ne 30) { throw "CREATE TABLE != 30" }
if ($checks['FOREIGN KEY']  -ne 24) { throw "FOREIGN KEY != 24" }
if ($checks['INSERT INTO']  -ne 12) { throw "INSERT INTO != 12" }

# Verificacion de seguridad: la salida NO debe contener datos personales
if ($all -match 'springrandalf|admin@andheuris|INSERT INTO \x60person\x60|INSERT INTO \x60user\x60|INSERT INTO \x60rol_user\x60') {
    throw "La salida contiene datos personales de la institucion de origen"
}
Write-Host "Verificacion de datos personales: OK (sin datos de la institucion de origen)"

# --- 6. Escritura UTF-8 sin BOM --------------------------------------------
$outDir = Split-Path $OutPath -Parent
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
[System.IO.File]::WriteAllText($OutPath, $all, (New-Object System.Text.UTF8Encoding($false)))
$len = (Get-Item $OutPath).Length
Write-Host "Generado: $OutPath ($len bytes)"
