# make-icons.ps1 —— 把图做成应用图标
#
# 做法很简单：整张图直接缩放，只加圆角，不做任何裁剪。
# 所以原图得是正方形的（当前是 700x700）。
#
# 换图标就改 $Src 指到新图上，然后重新跑这个脚本。

$Src = 'C:\Users\liu\Desktop\图标.png'
$Res = 'E:\Projects\jump-a11y\app\src\main\res'

Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

$src = [System.Drawing.Image]::FromFile($Src)
Write-Host "原图: $($src.Width) x $($src.Height)"

if ($src.Width -ne $src.Height) {
    Write-Host "  注意：不是正方形，会按短边居中裁一刀" -ForegroundColor Yellow
    $side = [Math]::Min($src.Width, $src.Height)
    $offX = [int](($src.Width - $side) / 2)
    $offY = [int](($src.Height - $side) / 2)
} else {
    $side = $src.Width
    $offX = 0
    $offY = 0
}
$cropRect = New-Object System.Drawing.Rectangle($offX, $offY, $side, $side)

# 各密度的 launcher 图标边长
$sizes = [ordered]@{
    'mipmap-mdpi'    = 48
    'mipmap-hdpi'    = 72
    'mipmap-xhdpi'   = 96
    'mipmap-xxhdpi'  = 144
    'mipmap-xxxhdpi' = 192
}

# 圆角半径占边长的比例。22% 接近 Android 自带图标的观感。
$radiusRatio = 0.22

function New-RoundedIcon {
    param([System.Drawing.Image]$Source, [System.Drawing.Rectangle]$Crop,
          [int]$Size, [string]$OutPath, [double]$Ratio)

    $bmp = New-Object System.Drawing.Bitmap($Size, $Size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.PixelOffsetMode = 'HighQuality'
    $g.Clear([System.Drawing.Color]::Transparent)

    $r = [int]($Size * $Ratio)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $path.AddArc(0, 0, $d, $d, 180, 90)
    $path.AddArc($Size - $d, 0, $d, $d, 270, 90)
    $path.AddArc($Size - $d, $Size - $d, $d, $d, 0, 90)
    $path.AddArc(0, $Size - $d, $d, $d, 90, 90)
    $path.CloseFigure()

    $g.SetClip($path)
    $g.DrawImage($Source, (New-Object System.Drawing.Rectangle(0, 0, $Size, $Size)),
                 $Crop, [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
    $bmp.Save($OutPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $path.Dispose()
}

foreach ($dir in $sizes.Keys) {
    $size = $sizes[$dir]
    $target = Join-Path $Res $dir
    New-Item -ItemType Directory -Force -Path $target | Out-Null

    New-RoundedIcon -Source $src -Crop $cropRect -Size $size `
        -OutPath (Join-Path $target 'ic_launcher.png') -Ratio $radiusRatio
    New-RoundedIcon -Source $src -Crop $cropRect -Size $size `
        -OutPath (Join-Path $target 'ic_launcher_round.png') -Ratio 0.5

    Write-Host ("  {0,-16} {1,4}px  方形 + 圆形" -f $dir, $size)
}

# 自适应图标的前景（Android 8+ 用）。
# 系统会把它裁成圆形/方形/水滴等各种形状，内容要留在中间 66% 的安全区里，
# 所以这里缩小居中、四周留透明。
$fgSize = 432
$safe = [int]($fgSize * 0.66)
$fgDir = Join-Path $Res 'drawable-nodpi'
New-Item -ItemType Directory -Force -Path $fgDir | Out-Null
$fgPath = Join-Path $fgDir 'ic_launcher_foreground.png'

$bmp = New-Object System.Drawing.Bitmap($fgSize, $fgSize)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.InterpolationMode = 'HighQualityBicubic'
$g.PixelOffsetMode = 'HighQuality'
$g.Clear([System.Drawing.Color]::Transparent)

# 裁成圆形，免得方形边角被系统切出硬边
$ox = [int](($fgSize - $safe) / 2)
$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$path.AddEllipse($ox, $ox, $safe, $safe)
$g.SetClip($path)
$g.DrawImage($src, (New-Object System.Drawing.Rectangle($ox, $ox, $safe, $safe)),
             $cropRect, [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()
$bmp.Save($fgPath, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
$path.Dispose()
Write-Host "  drawable-nodpi   ${fgSize}px  自适应图标前景"

# 背景色取图里的浅灰蓝，跟画面对得上
$bgXml = @'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#FFD8DCE6</color>
</resources>
'@
Set-Content -Path (Join-Path $Res 'values\ic_launcher_background.xml') -Value $bgXml -Encoding UTF8
Write-Host "  values/          ic_launcher_background.xml"

$anydpi = Join-Path $Res 'mipmap-anydpi-v26'
New-Item -ItemType Directory -Force -Path $anydpi | Out-Null
$adaptive = @'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
'@
Set-Content -Path (Join-Path $anydpi 'ic_launcher.xml') -Value $adaptive -Encoding UTF8
Set-Content -Path (Join-Path $anydpi 'ic_launcher_round.xml') -Value $adaptive -Encoding UTF8
Write-Host "  mipmap-anydpi-v26/  自适应图标描述"

$src.Dispose()
Write-Host ''
Write-Host '图标生成完毕（整图直出，只加圆角）。'
