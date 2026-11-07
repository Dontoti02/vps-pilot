<#
.SYNOPSIS
  Genera resources/icono.ico para BolsaDeploy Manager (sin herramientas externas).

.DESCRIPTION
  Dibuja la marca "B" sobre un degradado violeta con GDI+ y empaqueta los
  tamaños 16/32/48/64/128/256 en un contenedor ICO con entradas PNG
  (válido desde Windows Vista). Si se ejecuta sin GDI+ disponible avisa y
  continúa: jpackage solo perderá el icono propio.
#>
[CmdletBinding()]
param(
    [string]$Salida = (Join-Path $PSScriptRoot "..\resources\icono.ico")
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing

$tamanios = @(16, 32, 48, 64, 128, 256)
$imagenes = @()

foreach ($t in $tamanios) {
    $bmp = New-Object System.Drawing.Bitmap($t, $t)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = "AntiAlias"
    $g.TextRenderingHint = "AntiAliasGridFit"

    # Fondo: rectángulo redondeado con degradado violeta
    $rect = New-Object System.Drawing.Rectangle(0, 0, $t, $t)
    $ruta = New-Object System.Drawing.Drawing2D.GraphicsPath
    $r = [Math]::Max(2, [int]($t * 0.18))
    $w = [int]($t - 1); $h = [int]($t - 1)
    $d = [int]($r * 2)
    $ruta.AddArc(0, 0, $d, $d, 180, 90)
    $ruta.AddArc(($w - $d), 0, $d, $d, 270, 90)
    $ruta.AddArc(($w - $d), ($h - $d), $d, $d, 0, 90)
    $ruta.AddArc(0, ($h - $d), $d, $d, 90, 90)
    $ruta.CloseFigure()
    $pincel = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        $rect, [System.Drawing.Color]::FromArgb(255, 124, 58, 237),
        [System.Drawing.Color]::FromArgb(255, 76, 29, 149), 45)
    $g.FillPath($pincel, $ruta)

    # Flecha de "despliegue" (flecha hacia arriba dentro de un check estilizado)
    $pluma = New-Object System.Drawing.Pen([System.Drawing.Color]::White,
        ([Math]::Max(1.5, $t * 0.09)))
    $pluma.StartCap = "Round"; $pluma.EndCap = "Round"; $pluma.LineJoin = "Round"
    $cx = $t / 2.0; $cy = $t / 2.0; $s = $t * 0.22
    $pts = @(
        (New-Object System.Drawing.PointF($cx - $s, $cy + $s * 0.6)),
        (New-Object System.Drawing.PointF($cx - $s * 0.1, $cy + $s * 1.4)),
        (New-Object System.Drawing.PointF($cx + $s * 1.2, $cy - $s * 1.2))
    )
    $g.DrawLines($pluma, $pts)
    $g.Dispose()

    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $imagenes += ,@($t, $ms.ToArray())
    $bmp.Dispose()
}

# --- Contenedor ICO ---
$dir = Split-Path -Parent $Salida
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }

$fs = [System.IO.File]::Create($Salida)
$bw = New-Object System.IO.BinaryWriter($fs)
$bw.Write([uint16]0)            # reserved
$bw.Write([uint16]1)            # type = icon
$bw.Write([uint16]$imagenes.Count)

$offset = 6 + (16 * $imagenes.Count)
foreach ($img in $imagenes) {
    $t = $img[0]; $bytes = $img[1]
    $byteT = if ($t -ge 256) { 0 } else { [byte]$t }
    $bw.Write([byte]$byteT)     # width
    $bw.Write([byte]$byteT)     # height
    $bw.Write([byte]0)          # paleta
    $bw.Write([byte]0)          # reserved
    $bw.Write([uint16]1)        # planes
    $bw.Write([uint16]32)       # bpp
    $bw.Write([uint32]$bytes.Length)
    $bw.Write([uint32]$offset)
    $offset += $bytes.Length
}
foreach ($img in $imagenes) {
    $bw.Write($img[1])
}
$bw.Flush(); $fs.Close()

Write-Host "Icono generado: $Salida ($($imagenes.Count) tamanios)"
