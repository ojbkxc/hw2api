#Requires -Version 5.1
<#
.SYNOPSIS
    hw2api 真机验证脚本（PowerShell + adb）。

.DESCRIPTION
    在 vivo X60（或其他 Android 12+ 设备）上分步验证 hw2api：
      1.  确认设备在线
      2.  安装 APK（-r 覆盖安装）
      3.  启动 MainActivity
      4.  提示手动完成华为云登录（等待按键继续）
      5.  启动 API 前台服务（adb 直启失败时引导 App 内手动开启）
      6.  adb 端口转发 tcp:8788
      7.  GET /health 健康检查
      8.  GET /v1/models 模型列表非空
      9.  POST /v1/chat/completions 非流式冒烟（可选 -WithChat，消耗上游配额）
      10. POST /v1/chat/completions 流式冒烟  （可选 -WithChat，消耗上游配额）

    每步打印 [PASS]/[FAIL] 与中文说明，退出码：0 = 全部通过，1 = 有失败项。
    依赖 adb 端口转发，因此无需打开 App 的「局域网接入」开关（默认仅本机监听即可验证）。

.EXAMPLE
    # 基础验证（默认安装 ..\app\build\outputs\apk\debug\app-debug.apk）
    powershell -ExecutionPolicy Bypass -File .\verify_device.ps1

.EXAMPLE
    # 完整验证（含 chat 冒烟，消耗少量上游配额）
    powershell -ExecutionPolicy Bypass -File .\verify_device.ps1 -WithChat

.EXAMPLE
    # 指定 APK / 端口 / API Key
    powershell -ExecutionPolicy Bypass -File .\verify_device.ps1 -WithChat `
        -ApkPath C:\apk\app-debug.apk -Port 8788 -ApiKey "my-secret"
#>
param(
    # APK 路径：默认取脚本目录上一级（项目根）的标准 debug 产物
    [string]$ApkPath = "",

    # 目标设备序列号（vivo X60）
    [string]$Serial = "9587839428004US",

    # App 监听端口（需与 App 设置页一致，默认 8788）
    [int]$Port = 8788,

    # App 设置页配置的 API Key（启用鉴权时必须传入，否则 /v1/* 会 401）
    [string]$ApiKey = "",

    # 开关：执行第 9/10 步 chat 冒烟（会消耗上游配额）
    [switch]$WithChat
)

# ═══════════════════ 集中定义（按需修改） ═══════════════════

# adb 完整路径（本机 adb 不在 PATH，固定 C:\adb\adb.exe）
$adb = "C:\adb\adb.exe"

$packageName   = "com.lxseek.hw2api"
$mainActivity  = "com.lxseek.hw2api/com.lxseek.hw2api.ui.MainActivity"
$fgsService    = "com.lxseek.hw2api/com.lxseek.hw2api.service.ProxyForegroundService"

# ═══════════════════ 初始化 ═══════════════════

# 默认 APK：脚本目录 ..\app\build\outputs\apk\debug\app-debug.apk
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"
}
$ApkPath = [System.IO.Path]::GetFullPath($ApkPath)

$script:FailCount = 0

function Write-Pass([string]$msg) { Write-Host "[PASS] $msg" -ForegroundColor Green }
function Write-Fail([string]$msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; $script:FailCount++ }
function Write-Info([string]$msg) { Write-Host "[INFO] $msg" -ForegroundColor Cyan }
function Write-Step([string]$title) { Write-Host "`n========== $title ==========" -ForegroundColor Yellow }

# 等待用户在手机上完成操作后按回车
function Wait-UserAction([string]$msg) {
    Read-Host "$msg`n操作完成后按回车继续" | Out-Null
}

# 鉴权请求头（启用 API Key 时自动附加）
$authHeaders = @{}
if ($ApiKey -ne "") { $authHeaders = @{ Authorization = "Bearer $ApiKey" } }

Write-Host "hw2api 真机验证" -ForegroundColor White
Write-Host "设备: $Serial | 端口: $Port | APK: $ApkPath"
Write-Host "chat 冒烟: $(if ($WithChat) { '启用(-WithChat，消耗上游配额)' } else { '跳过(加 -WithChat 启用)' })"

# ═══════════════════ 前置检查：adb 可用 ═══════════════════

if (-not (Test-Path $adb)) {
    Write-Host "[FAIL] 未找到 adb：$adb（请安装 platform-tools，或修改脚本头部 `$adb 变量为实际路径）" -ForegroundColor Red
    exit 1
}

# ═══════════════════ 步骤 1：设备在线 ═══════════════════

Write-Step "步骤 1/10 确认设备在线"

$devicesOut = & $adb devices
$online = $devicesOut | Where-Object { $_ -match ("^" + [regex]::Escape($Serial) + "\s+device\s*$") }
if ($online) {
    Write-Pass "设备 $Serial 在线"
} else {
    $unauth = $devicesOut | Where-Object { $_ -match ("^" + [regex]::Escape($Serial) + "\s+unauthorized") }
    if ($unauth) {
        Write-Fail "设备 $Serial 未授权：请在手机上确认「允许 USB 调试」弹窗后重跑脚本"
    } else {
        Write-Fail "设备 $Serial 不在线（检查 USB 连接与「USB 调试」开关）。当前 adb devices 输出："
        $devicesOut | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    }
    exit 1
}

# ═══════════════════ 步骤 2：安装 APK ═══════════════════

Write-Step "步骤 2/10 安装 APK"

if (-not (Test-Path $ApkPath)) {
    Write-Fail "APK 不存在：$ApkPath"
    Write-Info "请先按 README.md 第 4 章构建 debug APK，或用 -ApkPath 参数指定 APK 路径"
    exit 1
}

Write-Info "正在安装（若手机弹出「USB 安装/允许安装」确认框，请在手机上点允许）..."
$installOut = (& $adb -s $Serial install -r "$ApkPath" 2>&1) -join " "
if ($installOut -match "Success") {
    Write-Pass "APK 安装成功"
} else {
    Write-Fail "APK 安装失败：$installOut"
    Write-Info "vivo 机型请确认已开启 开发者选项 → 「USB 安装」；也可卸载后重试：adb uninstall $packageName"
}

# ═══════════════════ 步骤 3：启动 App ═══════════════════

Write-Step "步骤 3/10 启动 HW API（MainActivity）"

$startOut = (& $adb -s $Serial shell am start -W -n "$mainActivity" 2>&1) -join " "
if ($startOut -match "Status:\s*ok") {
    Write-Pass "App 已启动"
} else {
    Write-Fail "App 启动失败：$startOut"
}

# ═══════════════════ 步骤 4：手动登录 ═══════════════════

Write-Step "步骤 4/10 手动登录华为云账号"

Write-Info "请在手机 App 内点击「登录华为云」，完成 WebView 授权页的账号登录"
Write-Info "登录成功后主界面账号卡会显示账号名与凭证倒计时"
Write-Info "若登录页白屏无法交互：见 README.md 第 8.3 节（JS 开关说明）"
Wait-UserAction "请在手机上完成登录"

# ═══════════════════ 步骤 5：启动前台服务 ═══════════════════

Write-Step "步骤 5/10 启动 API 前台服务"

Write-Info "尝试通过 adb 直接启动前台服务（Android 12 对非导出组件可能拒绝）..."
(& $adb -s $Serial shell am start-foreground-service -n "$fgsService" 2>&1) | Out-Null
Start-Sleep -Seconds 3

# 用 dumpsys 确认服务是否在运行（比对服务类名）
$svcDump = (& $adb -s $Serial shell "dumpsys activity services $packageName") -join "`n"
if ($svcDump -match "ProxyForegroundService") {
    Write-Pass "前台服务运行中（通知栏应有 HW API 常驻通知）"
} else {
    Write-Info "adb 直启未生效，请在手机 App 主界面打开「API 服务」开关"
    Wait-UserAction "请在 App 内打开「API 服务」开关（通知栏出现 HW API 通知）"
    $svcDump = (& $adb -s $Serial shell "dumpsys activity services $packageName") -join "`n"
    if ($svcDump -match "ProxyForegroundService") {
        Write-Pass "前台服务运行中（手动启动）"
    } else {
        Write-Fail "前台服务未运行：请检查 App 是否被系统限制后台（README.md 第 8.6 节）"
    }
}

# ═══════════════════ 步骤 6：端口转发 ═══════════════════

Write-Step "步骤 6/10 adb 端口转发 tcp:$Port"

$fwOut = (& $adb -s $Serial forward "tcp:$Port" "tcp:$Port" 2>&1) -join " "
if ($fwOut -match "^\s*\d+\s*$") {
    Write-Pass "端口转发已建立：电脑 127.0.0.1:$Port -> 手机 $Port"
} else {
    Write-Fail "端口转发失败：$fwOut"
    Write-Info "端口需与 App 设置页「监听端口」一致（可用 -Port 参数覆盖）"
}

# ═══════════════════ 步骤 7：健康检查 ═══════════════════

Write-Step "步骤 7/10 健康检查 GET /health"

try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 10
    $remainMin = [Math]::Floor($health.expires_in_seconds / 60)
    $summary = "status=$($health.status) | 账号=$($health.account) | 凭证剩余≈$remainMin 分钟 | 监听=$($health.listen) | 鉴权=$($health.auth_enabled)"
    switch ($health.status) {
        "ok"                  { Write-Pass "健康检查通过：$summary" }
        "no_account"          { Write-Fail "未登录账号（回到步骤 4 完成登录）：$summary" }
        "credentials_expired" { Write-Fail "凭证已过期且续期失败（请在 App 内重新登录）：$summary" }
        default               { Write-Fail "异常状态：$summary" }
    }
} catch {
    Write-Fail "请求 /health 失败：$($_.Exception.Message)"
    Write-Info "请确认 App 内服务开关已打开、端口与设置页一致"
}

# ═══════════════════ 步骤 8：模型列表 ═══════════════════

Write-Step "步骤 8/10 模型列表 GET /v1/models"

try {
    $models = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/v1/models" -Headers $authHeaders -TimeoutSec 10
    $ids = @($models.data | ForEach-Object { $_.id })
    if ($ids.Count -ge 5) {
        Write-Pass "模型列表返回 $($ids.Count) 个条目：$($ids -join ', ')"
    } else {
        Write-Fail "模型列表为空或异常（仅 $($ids.Count) 条）"
    }
} catch {
    $msg = $_.Exception.Message
    if ($msg -match "401") {
        Write-Fail "401 未授权：App 已启用 API Key，请用 -ApiKey 参数传入正确的 Key"
    } else {
        Write-Fail "请求 /v1/models 失败：$msg"
    }
}

# ═══════════════════ 步骤 9：非流式 chat 冒烟（可选） ═══════════════════

if ($WithChat) {
    Write-Step "步骤 9/10 非流式 chat 冒烟（消耗上游配额）"

    # here-string 定义 JSON 请求体（避免 PowerShell 引号转义问题）
    $chatBody = @'
{"model":"openpangu-2.0-pro","messages":[{"role":"user","content":"你好"}],"stream":false}
'@

    try {
        # body 转 UTF-8 字节数组发送，确保中文不乱码（PowerShell 5.1 字符串 body 编码坑）
        $resp = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/v1/chat/completions" `
            -ContentType "application/json; charset=utf-8" -Headers $authHeaders `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($chatBody)) -TimeoutSec 120
        $content = ""
        if ($resp.choices -and $resp.choices.Count -gt 0 -and $resp.choices[0].message.content) {
            $content = [string]$resp.choices[0].message.content
        }
        if ($content -ne "") {
            $preview = $content.Substring(0, [Math]::Min(60, $content.Length))
            Write-Pass "非流式响应正常，回复预览：$preview"
        } else {
            Write-Fail "响应缺少 choices[0].message.content：$($resp | ConvertTo-Json -Depth 5 -Compress)"
        }
    } catch {
        Write-Fail "非流式 chat 请求失败：$($_.Exception.Message)"
        Write-Info "可运行 .\pull_logs.ps1 取证；若 401 relogin_required 请在 App 内重新登录"
    }
} else {
    Write-Info "跳过步骤 9/10（加 -WithChat 参数执行，会消耗上游配额）"
}

# ═══════════════════ 步骤 10：流式 chat 冒烟（可选） ═══════════════════

if ($WithChat) {
    Write-Step "步骤 10/10 流式 chat 冒烟 - SSE（消耗上游配额）"

    # Invoke-WebRequest 会缓冲整个 SSE 响应，结束后统一校验帧结构
    $streamBody = @'
{"model":"openpangu-2.0-pro","messages":[{"role":"user","content":"用一句话介绍你自己"}],"stream":true}
'@

    try {
        $sw = Invoke-WebRequest -Method Post -Uri "http://127.0.0.1:$Port/v1/chat/completions" `
            -ContentType "application/json; charset=utf-8" -Headers $authHeaders `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($streamBody)) -TimeoutSec 180 -UseBasicParsing
        $text = [string]$sw.Content
        $hasData      = $text -match "data:"
        $hasChunk     = $text -match "chat\.completion\.chunk"
        $hasDone      = $text -match "\[DONE\]"
        $frameCount   = ([regex]::Matches($text, "data:")).Count
        if ($hasData -and $hasChunk -and $hasDone) {
            Write-Pass "SSE 流式响应正常（$frameCount 个 data 帧，含 chunk 增量与 [DONE] 结束帧）"
        } else {
            Write-Fail "流式响应不完整：data帧=$hasData / chunk标记=$hasChunk / DONE标记=$hasDone"
            $text.Substring(0, [Math]::Min(300, $text.Length)) | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
        }
    } catch {
        Write-Fail "流式 chat 请求失败：$($_.Exception.Message)"
    }
} else {
    Write-Info "跳过步骤 10/10（加 -WithChat 参数执行，会消耗上游配额）"
}

# ═══════════════════ 汇总与收尾 ═══════════════════

Write-Host "`n========== 验证汇总 ==========" -ForegroundColor Yellow

# 清理端口转发（幂等，失败不影响结果）
& $adb -s $Serial forward --remove "tcp:$Port" 2>$null | Out-Null

if ($script:FailCount -eq 0) {
    Write-Host "全部步骤通过（$($script:FailCount) 失败）。手机可长期作为局域网 API 网关使用。" -ForegroundColor Green
    exit 0
} else {
    Write-Host "$($script:FailCount) 个步骤失败。可运行 pull_logs.ps1 拉取日志取证，并对照 README.md 第 8 章排查。" -ForegroundColor Red
    exit 1
}