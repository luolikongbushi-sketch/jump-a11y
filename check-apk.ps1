# 自查脚本：看看打进包里的到底是什么。
#
# 无障碍权限比较敏感，所以我习惯每次发布前跑一遍，确认没有多申请权限、
# 没有奇怪的依赖、没有原生库。
#
# 用法（先编译一次）：
#   powershell -ExecutionPolicy Bypass -File .\check-apk.ps1
#
# 下面这几个路径按自己的环境改。
$SdkRoot     = 'E:\Android\sdk'
$BuildTools  = '34.0.0'
$GradleBat   = 'E:\Android\gradle\gradle-8.5\bin\gradle.bat'
$JdkHome     = 'C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot'

$ErrorActionPreference = 'Continue'
$Proj = Split-Path -Parent $MyInvocation.MyCommand.Path
$Apk = Join-Path $Proj 'app\build\outputs\apk\debug\app-debug.apk'
$aapt2 = Join-Path $SdkRoot "build-tools\$BuildTools\aapt2.exe"
$apksig = Join-Path $SdkRoot "build-tools\$BuildTools\apksigner.bat"

function 标题($t) { Write-Host ''; Write-Host "===== $t =====" -ForegroundColor Cyan }
function 通过($m) { Write-Host "  [通过] $m" -ForegroundColor Green }
function 失败($m) { Write-Host "  [失败] $m" -ForegroundColor Red }
function 提示($m) { Write-Host "  [提示] $m" -ForegroundColor DarkGray }

if (-not (Test-Path $Apk)) { 失败 "还没编译过，找不到 $Apk"; exit 1 }
if (-not (Test-Path $aapt2)) { 失败 "找不到 aapt2，检查 `$SdkRoot"; exit 1 }

# ---------------------------------------------------------------- 1 源码扫描
标题 '1. 源码里有没有不该出现的东西'
$kt = Get-ChildItem (Join-Path $Proj 'app\src') -Recurse -Include '*.kt', '*.java' -ErrorAction SilentlyContinue
提示 "扫描 $($kt.Count) 个源文件"

$危险写法 = [ordered]@{
    '执行 shell / root'      = 'Runtime\.getRuntime|ProcessBuilder|\.exec\(|/system/bin|/system/xbin'
    '反射'                   = 'Class\.forName|getDeclaredMethod|java\.lang\.reflect'
    '动态加载代码'           = 'DexClassLoader|PathClassLoader|loadLibrary|System\.load'
    '网络'                   = 'HttpURLConnection|OkHttp|Socket\(|WebView'
    '读写外部存储'           = 'getExternalStorage|/sdcard/|Environment\.getExternal'
    '读取联系人/短信/电话'   = 'ContactsContract|TelephonyManager|SmsManager'
    '截屏'                   = 'MediaProjection|takeScreenshot'
    '剪贴板'                 = 'ClipboardManager|getPrimaryClip'
    '安装/卸载应用'          = 'PackageInstaller|deletePackage|ACTION_INSTALL_PACKAGE'
    '自启 / 定时任务'        = 'BOOT_COMPLETED|AlarmManager|setRepeating'
    '读屏 / 遍历控件'        = 'getRootInActiveWindow|performGlobalAction|AccessibilityNodeInfo'
    '前台服务'               = 'startForeground'
}
$有问题 = $false
foreach ($名称 in $危险写法.Keys) {
    $命中 = @()
    foreach ($f in $kt) {
        $m = Select-String -Path $f.FullName -Pattern $危险写法[$名称] -ErrorAction SilentlyContinue
        if ($m) { $命中 += $m }
    }
    if ($命中.Count -gt 0) {
        $有问题 = $true
        失败 "$名称 命中 $($命中.Count) 处"
        $命中 | Select-Object -First 3 | ForEach-Object { Write-Host "         $($_.Filename):$($_.LineNumber)  $($_.Line.Trim())" }
    } else {
        通过 "$名称 —— 没有"
    }
}
if (-not $有问题) { Write-Host '  => 源码干净' -ForegroundColor Green }

# ---------------------------------------------------------------- 2 权限
标题 '2. 打进包里的权限（以包里的为准）'
$tree = & $aapt2 dump xmltree --file AndroidManifest.xml $Apk 2>&1
$joined = $tree -join "`n"

Write-Host '  —— 申请的权限 ——'
$权限 = [regex]::Matches($joined, 'android\.permission\.[A-Z_0-9]+') |
    ForEach-Object { $_.Value } | Sort-Object -Unique
if ($权限) { $权限 | ForEach-Object { Write-Host "     $_" } } else { 提示 '（没有）' }

Write-Host ''
$联网类 = @('INTERNET', 'ACCESS_NETWORK_STATE', 'ACCESS_WIFI_STATE')
$有联网 = $false
foreach ($p in $联网类) { if ($joined -match $p) { 失败 "有 $p"; $有联网 = $true } }
if (-not $有联网) { 通过 '没有任何联网权限 —— 想联网也联不了' }

Write-Host ''
$敏感 = @('RECORD_AUDIO', 'CAMERA', 'READ_CONTACTS', 'READ_SMS', 'RECEIVE_SMS', 'ACCESS_FINE_LOCATION',
    'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE', 'MANAGE_EXTERNAL_STORAGE', 'SYSTEM_ALERT_WINDOW',
    'PACKAGE_USAGE_STATS', 'REQUEST_INSTALL_PACKAGES', 'BIND_DEVICE_ADMIN', 'RECEIVE_BOOT_COMPLETED',
    'FOREGROUND_SERVICE', 'QUERY_ALL_PACKAGES', 'READ_PHONE_STATE', 'GET_ACCOUNTS', 'USE_EXACT_ALARM')
$命中敏感 = @()
foreach ($p in $敏感) { if ($joined -match $p) { $命中敏感 += $p } }
if ($命中敏感.Count -eq 0) { 通过 "这 $($敏感.Count) 项敏感权限一个都没有" }
else { 失败 "命中：$($命中敏感 -join ', ')" }

# ---------------------------------------------------------------- 3 无障碍配置
标题 '3. 无障碍服务的配置'
$cfg = & $aapt2 dump xmltree --file res/xml/accessibility_service_config.xml $Apk 2>&1
$cfgText = $cfg -join "`n"
$cfg | Select-String -Pattern 'A: ' | ForEach-Object { Write-Host "     $($_.Line.Trim())" }

if ($cfgText -match 'canPerformGestures\(0x0101050d\)=true') {
    通过 'canPerformGestures = true（能注入手势）'
} else { 失败 'canPerformGestures 不是 true，手势注入会失效' }

if ($cfgText -match 'canRetrieveWindowContent\(0x01010385\)=false') {
    通过 'canRetrieveWindowContent = false（读不了别的内容）'
} elseif ($cfgText -match 'canRetrieveWindowContent') {
    失败 'canRetrieveWindowContent 是 true —— 这个应用能读屏幕内容'
} else { 通过 'canRetrieveWindowContent 没设（默认 false）' }

# ---------------------------------------------------------------- 4 签名
标题 '4. 签名'
if (Test-Path $apksig) {
    & $apksig verify --print-certs $Apk 2>&1 | Select-Object -First 3 | ForEach-Object { Write-Host "     $_" }
    Write-Host ''
    & $apksig verify -v $Apk 2>&1 | Select-String -Pattern 'Verified using|Number of signers' |
        ForEach-Object { Write-Host "     $($_.Line.Trim())" }
} else { 提示 '找不到 apksigner，跳过' }

# ---------------------------------------------------------------- 5 原生库和依赖
标题 '5. 原生库和依赖'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
$so = $zip.Entries | Where-Object { $_.FullName -like 'lib/*' }
if ($so) {
    失败 "有 $($so.Count) 个原生库"
    $so | ForEach-Object { Write-Host "     $($_.FullName)" }
} else {
    通过 '没有任何 .so 原生库（没有看不懂的机器码）'
}
$dex = $zip.Entries | Where-Object { $_.FullName -like '*.dex' } | ForEach-Object { $_.FullName }
提示 "dex：$($dex -join ', ')"
$zip.Dispose()

Write-Host ''
$env:JAVA_HOME = $JdkHome
$env:GRADLE_USER_HOME = Join-Path (Split-Path $GradleBat -Parent) '..\home'
Push-Location $Proj
if (Test-Path $GradleBat) {
    & $GradleBat -q :app:dependencies --configuration debugRuntimeClasspath --console=plain 2>&1 |
        Select-String -Pattern '^[+\\]---' | ForEach-Object { Write-Host "     $($_.Line)" }
} else {
    提示 "找不到 gradle，跳过依赖列表：$GradleBat"
}
Pop-Location

Write-Host ''
Write-Host '===== 检查完毕 =====' -ForegroundColor Cyan
