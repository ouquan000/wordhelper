<#
.SYNOPSIS
    不爱背单词 1.5.1 模拟器端到端冒烟脚本（5 项硬闸门）。

.DESCRIPTION
    默认针对 1080x2340 的 API 34 模拟器（AVD: mf）。
    前置条件：
      1. 模拟器已启动（adb devices 能看到 emulator-5554 device）。
      2. 电脑端 tools/cardback-editor/server.py 已在 8765 端口运行。
      3. 手机设置里「卡背结构地址」已填 http://10.0.2.2:8765（本脚本不负责改设置，
         避免用固定坐标误触其它开关；首次跑请先在「我的」页填一次）。

    产出：
      screenshots\01_home.png ... 05_cold_start.png
      emulator_smoke.log

.EXAMPLE
    pwsh -File scripts/emulator_smoke.ps1
#>
param(
    [string]$Serial = "emulator-5554",
    [string]$Apk = "app/src_repo/app/build/outputs/apk/prod/release/app-prod-release.apk",
    [string]$ExpectedVersion = "1.5.1",
    [string]$OutDir = "artifacts/emulator-smoke"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$adb = "D:\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "找不到 adb: $adb" }
if (-not [System.IO.Path]::IsPathRooted($Apk)) { $Apk = Join-Path $repo $Apk }
if (-not (Test-Path $Apk)) { throw "找不到 APK: $Apk" }
if (-not [System.IO.Path]::IsPathRooted($OutDir)) { $OutDir = Join-Path $repo $OutDir }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$script:logPath = Join-Path $OutDir "emulator_smoke.log"
$script:failures = New-Object System.Collections.Generic.List[string]
function Log([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
    Write-Host $line
    Add-Content -LiteralPath $script:logPath -Value $line -Encoding utf8
}
function Adb([string[]]$AdbArgs, [switch]$AllowFail) {
    $out = & $adb -s $Serial @AdbArgs 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $AllowFail) { throw "adb $($AdbArgs -join ' ') 失败: $out" }
    return $out
}
function Get-Ui {
    Adb @("shell", "uiautomator", "dump", "/sdcard/_smoke.xml") | Out-Null
    $xmlText = (Adb @("shell", "cat", "/sdcard/_smoke.xml")) -join "`n"
    return [xml]$xmlText
}
function Find-Node([xml]$ui, [string]$pattern) {
    return $ui.SelectNodes("//node") | Where-Object { $_.text -match $pattern -or $_.'content-desc' -match $pattern } | Select-Object -First 1
}
function Tap-Text([xml]$ui, [string]$pattern) {
    $node = Find-Node $ui $pattern
    if (-not $node) { throw "界面上找不到「$pattern」" }
    if ($node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw "节点没有 bounds: $pattern" }
    $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
    $x = [int](($x1 + $x2) / 2)
    $y = [int](($y1 + $y2) / 2)
    Adb @("shell", "input", "tap", "$x", "$y") | Out-Null
    Log "tap $pattern @($x,$y)"
}
function Wait-Text([string]$pattern, [int]$seconds = 15) {
    $deadline = (Get-Date).AddSeconds($seconds)
    do {
        $ui = Get-Ui
        if (Find-Node $ui $pattern) { return $ui }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "等待「$pattern」超时（${seconds}s）"
}
function Screenshot([string]$name) {
    $path = Join-Path $OutDir "$name.png"
    Adb @("shell", "screencap", "-p", "/sdcard/_smoke.png") | Out-Null
    Adb @("pull", "/sdcard/_smoke.png", $path) | Out-Null
    Log "截图 $path"
}
function Swipe-Up([int]$times = 1) {
    1..$times | ForEach-Object { Adb @("shell", "input", "swipe", "540", "2100", "540", "600", "450") | Out-Null; Start-Sleep -Milliseconds 500 }
}
function Swipe-Down([int]$times = 1) {
    1..$times | ForEach-Object { Adb @("shell", "input", "swipe", "540", "600", "540", "2100", "450") | Out-Null; Start-Sleep -Milliseconds 500 }
}

Log "=== 不爱背单词模拟器冒烟（$ExpectedVersion）==="
Log "APK: $Apk"
$devices = (& $adb devices) -join "`n"
if ($devices -notmatch ([regex]::Escape($Serial) + "\s+device")) { throw "模拟器 $Serial 不在线：$devices" }

# 0. 安装 + 版本
Adb @("install", "-r", $Apk) | Out-Null
$pkg = (Adb @("shell", "dumpsys", "package", "com.kaoyan.wordhelper")) -join "`n"
if ($pkg -notmatch "versionName=$([regex]::Escape($ExpectedVersion))") { throw "安装后的版本不是 $ExpectedVersion" }
Log "安装成功，版本 $ExpectedVersion"

Adb @("logcat", "-c") | Out-Null
Adb @("shell", "am", "force-stop", "com.kaoyan.wordhelper") | Out-Null
Adb @("shell", "monkey", "-p", "com.kaoyan.wordhelper", "-c", "android.intent.category.LAUNCHER", "1") | Out-Null
$ui = Wait-Text "轻触卡片查看释义与例句" 20
Log "PASS 1/5 冷启动进入学习页"
Screenshot "01_home"

# 1. 自动备份：启动 4 秒后应落一份非空 db
Start-Sleep -Seconds 6
$backups = ((Adb @("shell", "ls", "-l", "/sdcard/Download/WordHelperBackups/")) -join "`n")
if ($backups -notmatch 'kaoyan_words_\d{8}_\d{4}\.db') { throw "Download/WordHelperBackups 下没有备份文件：$backups" }
Log "PASS 2/5 自动备份落盘：$($backups.Trim())"

# 2. 词卡热更 + 冷启动回读
Tap-Text $ui "打开菜单"; Start-Sleep -Milliseconds 500
$ui = Get-Ui; Tap-Text $ui "我的"; Start-Sleep -Seconds 1
Swipe-Up 1
$ui = Wait-Text "同步最新词卡" 10
Tap-Text $ui "同步最新词卡"
$ui = Wait-Text "已更新|上次同步|已是最新" 30
Screenshot "02_wordbook_sync"
Log "PASS 3/5 词卡热更：$((Find-Node $ui '已更新|上次同步|已是最新').text)"

Adb @("shell", "am", "force-stop", "com.kaoyan.wordhelper") | Out-Null
Adb @("shell", "monkey", "-p", "com.kaoyan.wordhelper", "-c", "android.intent.category.LAUNCHER", "1") | Out-Null
Start-Sleep -Seconds 4
$ui = Get-Ui; Tap-Text $ui "打开菜单"; Start-Sleep -Milliseconds 500
$ui = Get-Ui; Tap-Text $ui "我的"; Start-Sleep -Seconds 1
Swipe-Up 1
$ui = Wait-Text "上次同步：" 10
Screenshot "03_cold_start_state"
Log "PASS 4/5 冷启动回读：$((Find-Node $ui '上次同步：').text)"

# 3. 翻卡 + 重写思路即时反馈
$ui = Get-Ui; Tap-Text $ui "打开菜单"; Start-Sleep -Milliseconds 500
$ui = Get-Ui; Tap-Text $ui "学习"; Start-Sleep -Seconds 1
$ui = Get-Ui
Tap-Text $ui "轻触卡片查看释义与例句|cognitive|increase"
Start-Sleep -Seconds 1
$ui = Get-Ui
if (Find-Node $ui "轻触卡片查看释义与例句") {
    # 文本节点本身不一定翻卡，补一记卡片中心
    Adb @("shell", "input", "tap", "540", "1200") | Out-Null
    Start-Sleep -Seconds 1
}
$ui = Get-Ui
for ($i = 0; $i -lt 8 -and -not (Find-Node $ui "重写思路"); $i++) {
    Adb @("shell", "input", "swipe", "540", "1750", "540", "1000", "450") | Out-Null
    Start-Sleep -Milliseconds 500
    $ui = Get-Ui
}
if (-not (Find-Node $ui "重写思路")) { throw "卡背滑到底也没找到「重写思路」" }
Screenshot "04_before_rewrite"
Tap-Text $ui "重写思路"
$ui = Wait-Text "已标记|已送电脑|生成中|已更新|等电脑" 5
Screenshot "05_after_rewrite"
Log "PASS 5/5 重写思路有即时反馈：$((Find-Node $ui '已标记|已送电脑|生成中|已更新|等电脑').text)"

# 4. 评分切词
$ui = Get-Ui
Tap-Text $ui "✓ 认识"
Start-Sleep -Seconds 2
$ui = Get-Ui
if (-not (Find-Node $ui "学习进度")) { throw "评分后没有回到学习页" }
Log "PASS 背词评分切词：$((Find-Node $ui '\d{2}/\d+').text)"

# 5. 无 FATAL
$fatal = (Adb @("logcat", "-d", "-v", "brief") -join "`n") | Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime.*FATAL"
if ($fatal) { throw "logcat 发现 FATAL：$fatal" }
Log "PASS 无 FATAL 异常"
Log "=== 冒烟完成 ==="







