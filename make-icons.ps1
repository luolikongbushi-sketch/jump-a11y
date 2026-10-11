# make-icons.ps1 —— 把图做成应用图标
#
# 做法：整张图直接铺满，无边框、无圆角、不裁剪。
# 原图得是正方形的（当前 700x700）。
#
# 换图标就改 $Src 指到新图上，然后重新跑这个脚本。
#
# 另外脚本会把自适应图标和圆形图标删掉，原因见文件末尾。

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

# 圆角半径占边长的比例。0 = 直角，铺满无边框。
$radiusRatio = 0.0

function New-Icon {
    param([System.Drawing.Image]$Source, [System.Drawing.Rectangle]$Crop,
          [int]$Size, [string]$OutPath, [double]$Ratio)

    $bmp = New-Object System.Drawing.Bitmap($Size, $Size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.PixelOffsetMode = 'HighQuality'
    $g.Clear([System.Drawing.Color]::Transparent)

    $dest = New-Object System.Drawing.Rectangle(0, 0, $Size, $Size)

    if ($Ratio -le 0) {
        # 直角：直接铺满，不裁
        $g.DrawImage($Source, $dest, $Crop, [System.Drawing.GraphicsUnit]::Pixel)
    } else {
        $r = [int]($Size * $Ratio)
        $path = New-Object System.Drawing.Drawing2D.GraphicsPath
        $d = $r * 2
        $path.AddArc(0, 0, $d, $d, 180, 90)
        $path.AddArc($Size - $d, 0, $d, $d, 270, 90)
        $path.AddArc($Size - $d, $Size - $d, $d, $d, 0, 90)
        $path.AddArc(0, $Size - $d, $d, $d, 90, 90)
        $path.CloseFigure()
        $g.SetClip($path)
        $g.DrawImage($Source, $dest, $Crop, [System.Drawing.GraphicsUnit]::Pixel)
        $path.Dispose()
    }

    $g.Dispose()
    $bmp.Save($OutPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

foreach ($dir in $sizes.Keys) {
    $size = $sizes[$dir]
    $target = Join-Path $Res $dir
    New-Item -ItemType Directory -Force -Path $target | Out-Null

    New-Icon -Source $src -Crop $cropRect -Size $size `
        -OutPath (Join-Path $target 'ic_launcher.png') -Ratio $radiusRatio

    Write-Host ("  {0,-16} {1,4}px" -f $dir, $size)
}

# ---- 下面这些会让图标出现边框或变形，一并删掉 ----
#
# 1. 圆形版本：桌面如果用它会切成圆形，跟"无边框"冲突。
# 2. 自适应图标（mipmap-anydpi-v26）：Android 规定它必须留安全区，内容只能占中间约 66%，
#    四周会露出背景色 —— 那就是看到的那圈边框。想要真正无边框就不能用它，
#    让桌面直接拿这张方的 PNG 来显示。
foreach ($dir in $sizes.Keys) {
    $p = Join-Path (Join-Path $Res $dir) 'ic_launcher_round.png'
    if (Test-Path $p) { Remove-Item $p -Force }
}

$anydpi = Join-Path $Res 'mipmap-anydpi-v26'
if (Test-Path $anydpi) { Remove-Item $anydpi -Recurse -Force }

$fg = Join-Path $Res 'drawable-nodpi\ic_launcher_foreground.png'
if (Test-Path $fg) { Remove-Item $fg -Force }

$bg = Join-Path $Res 'values\ic_launcher_background.xml'
if (Test-Path $bg) { Remove-Item $bg -Force }

Write-Host ''
Write-Host '图标生成完毕：整图铺满，零圆角。'
$src.Dispose()
