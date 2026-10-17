#Requires -Version 5.1
<#
.SYNOPSIS
    hw2api 手机日志取证脚本（PowerShell + adb logcat）。

.DESCRIPTION
    拉取手机上 hw2api（包名 com.lxseek.hw2api）的 logcat 日志并筛查崩溃：

      1. 确认设备在线；
      2. 取 App 进程 pid：进程在跑 → logcat --pid 精确拉取；
         进程不在 → 拉全量后按包名过滤（服务被杀 / 崩溃退出场景）；
      3. 单独拉取 crash 缓冲区（logcat -b crash，崩溃专用缓冲）；
      4. 扫描 FATAL EXCEPTION / AndroidRuntime 崩溃关键字，屏幕输出摘要；
      5. 全部文件落盘到脚本同级的 logs\ 目录（文件名带时间戳）。

    退出码：0 = 无崩溃迹象；1 = 执行错误（设备不在线等）；2 = 发现崩溃记录。

    说明：App 本身遵循零日志原则（不主动写日志、不打凭证日志），
    logcat 中 hw2api 相关内容以系统消息与崩溃栈为主，取证重点看 crash 缓冲区。

.EXAMPLE
    # 基础用法（默认 vivo X60）
    powershell -ExecutionPolicy Bypass -File .\pull_logs.ps1

.EXAMPLE
    # 指定其他设备
    powershell -ExecutionPolicy Bypass -File .\pull_logs.ps1 -Serial <序列号>
#>
param(
    # 目标设备序列号（vivo X60）
    [string]$Serial = "9587839428004US"
)

# ═══════════════════ 集中定义（按需修改） ═══════════════════

# adb 完整路径（本机 adb 不在 PATH，固定 C:\adb\adb.exe）
$adb = "C:\adb\adb.exe"

$packageName = "com.lxseek.hw2api"

# 日志输出目录：脚本同级 logs\
$logDir = Join-Path $PSScriptRoot "logs"

# ═══════════════════ 初始化 ═══════════════════

function Write-Info([string]$msg) { Write-Host "[INFO] $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "[OK]   $msg" -ForegroundColor Green }
function Write-Warn2([string]$msg){ Write-Host "[WARN] $msg" -ForegroundColor Yellow }

# 让 PowerShell 按 UTF-8 解码 adb 输出，避免中文消息乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

if (-not (Test-Path $adb)) {
    Write-Host "[FAIL] 未找到 adb：$adb（请安装 platform-tools，或修改脚本头部 `$adb 变量为实际路径）" -ForegroundColor Red
    exit 1
}

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"

# ═══════════════════ 步骤 1：设备在线 ═══════════════════

$devicesOut = & $adb devices
$online = $devicesOut | Where-Object { $_ -match ("^" + [regex]::Escape($Serial) + "\s+device\s*$") }
if (-not $online) {
    Write-Host "[FAIL] 设备 $Serial 不在线。当前 adb devices 输出：" -ForegroundColor Red
    $devicesOut | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    exit 1
}
Write-Ok "设备在线：$Serial"

# ═══════════════════ 步骤 2：取进程 pid 并拉取日志 ═══════════════════

# 注意：PowerShell 的 $pid 是只读自动变量（当前 PowerShell 进程号），此处必须换名
$procIdOut = (& $adb -s $Serial shell pidof $packageName 2>$null) -join ""
$targetPid = $procIdOut.Trim()

$writtenFiles = @()

if ($targetPid -ne "") {
    Write-Info "App 进程运行中（pid=$targetPid），按 pid 精确拉取 logcat..."
    $procLog = & $adb -s $Serial shell "logcat -d --pid=$targetPid"
    $procLogFile = Join-Path $logDir "hw2api_proc_$stamp.log"
    $procLog | Out-File -FilePath $procLogFile -Encoding utf8
    $writtenFiles += $procLogFile
    Write-Ok "进程日志已保存：$procLogFile（$(@($procLog).Count) 行）"
} else {
    Write-Info "App 进程未运行（可能已被系统杀死或已崩溃退出），拉全量 logcat 后按包名过滤..."
    $filteredLog = & $adb -s $Serial shell "logcat -d" | Select-String -Pattern $packageName
    $filteredLogFile = Join-Path $logDir "hw2api_filtered_$stamp.log"
    $filteredLog | Out-File -FilePath $filteredLogFile -Encoding utf8
    $writtenFiles += $filteredLogFile
    Write-Ok "包名过滤日志已保存：$filteredLogFile（$(@($filteredLog).Count) 行）"
}

# ═══════════════════ 步骤 3：crash 缓冲区 ═══════════════════

Write-Info "拉取 crash 缓冲区（logcat -b crash）..."
$crashLog = & $adb -s $Serial shell "logcat -d -b crash"
$crashLogFile = Join-Path $logDir "hw2api_crash_$stamp.log"
$crashLog | Out-File -FilePath $crashLogFile -Encoding utf8
$writtenFiles += $crashLogFile
Write-Ok "崩溃缓冲日志已保存：$crashLogFile（$(@($crashLog).Count) 行）"

# ═══════════════════ 步骤 4：崩溃关键字筛查 ═══════════════════

# 崩溃判定：crash 缓冲区或进程日志中出现 FATAL EXCEPTION / AndroidRuntime 崩溃标记
$crashPattern = "FATAL EXCEPTION|AndroidRuntime"
$crashHits = @()
foreach ($file in $writtenFiles) {
    $crashHits += Get-Content -Path $file -Encoding UTF8 | Select-String -Pattern $crashPattern
}

if ($crashHits.Count -gt 0) {
    Write-Host "`n[FAIL] 检测到崩溃记录（$($crashHits.Count) 处 FATAL/AndroidRuntime 标记）：" -ForegroundColor Red
    $crashHits | Select-Object -First 30 | ForEach-Object { Write-Host "  $($_.Line)" -ForegroundColor Red }
    if ($crashHits.Count -gt 30) { Write-Host "  ...（仅显示前 30 行，完整内容见 logs\ 目录）" -ForegroundColor DarkGray }
    Write-Host "完整崩溃栈见：$crashLogFile" -ForegroundColor Yellow
    exit 2
} else {
    Write-Ok "未检测到 FATAL / AndroidRuntime 崩溃记录"
    Write-Host "`n本次取证输出文件：" -ForegroundColor White
    $writtenFiles | ForEach-Object { Write-Host "  $_" }
    exit 0
}