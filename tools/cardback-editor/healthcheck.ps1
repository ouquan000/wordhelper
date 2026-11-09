<#
.SYNOPSIS
    卡背编辑器 / Cloudflare 隧道 健康检查（只读探测 + 可选重启计划任务）

.DESCRIPTION
    定时（例如每 5 分钟，用「任务计划程序」）跑一次：
      1. 请求 -Url（默认 http://127.0.0.1:8765/health），看 HTTP 状态 + JSON 里的 ok / version；
      2. 正常 → 退出码 0，默认不写日志（避免日志把 D 盘堆满）；
      3. 异常 → 写一行带时间戳的日志，退出码 1；
         如果给了 -TaskName，还会重启那个计划任务（-DryRun 时只记「本应重启」不真动）。

    为什么默认查 127.0.0.1 而不是域名：
      查本机 8765 = 只验「服务活着」；查域名 = 连隧道一起验，但需要 token，
      而且要等 DNS/隧道都通。日常守护建议只查本机（快、不受外网影响），
      阶段性验收时再查域名。

    为什么要有「/health 不存在也要能判断」：
      1.4.6.2 发布版、以及当前还在跑的 8765 进程，都还没有 /health 路由（1.4.7 才加；
      工作区代码已经补上，但没重启就不生效），直接查会拿到 404。所以本脚本在 404 时会退回探测 -FallbackUrl
      （默认 /cardback.json）：能拿到 200 或 401 就说明「进程活着、只是没这接口」，
      记为 old-server（退出码 0 + 一条 WARN），而不是误判成「服务挂了」然后白重启。

.PARAMETER Url
    要探测的地址。默认本机 /health。
    验收隧道时可传：-Url https://anki.ouquansdiary.cn/health（需配合 -Token 或 -TokenFile）。

.PARAMETER LogPath
    日志文件。默认：本脚本同目录 healthcheck.log。
    注意：`*.log` 已被根 .gitignore 忽略（不会进 git）；但仓库可能被整体备份/清理，
    更稳的做法是指到仓库外，例如 D:\cardback-logs\healthcheck.log。

.PARAMETER TaskName
    「任务计划程序」里的任务名。只在判定异常时用；给了就重启它。
    典型值：cloudflared 那个自启任务（例如 "Cardback Cloudflared"）。
    ⚠️ 如果 -Url 查的是本机 8765，那么「8765 挂了」重启 cloudflared 是没用的，
      这时 -TaskName 应该填守护 8765 的那个任务名。
    ⚠️ 不给 -TaskName = 只报警不重启（更安全，推荐先用这个观察几天）。

.PARAMETER FallbackUrl
    /health 返回 404 时的退路，默认 http://127.0.0.1:8765/cardback.json。
    这个接口在本机（loopback、无 CF-Connecting-IP）不需要 token。
    传空字符串 "" 可以关掉这个退路。

.PARAMETER ExpectVersion
    期望的 version 值（例如 1.4.7）。给了就核对；不一致记为 version-mismatch（退出码 1），
    用来发现「电脑端还是旧进程」。不给就不核对。

.PARAMETER Token
    X-Cardback-Token 的值。只有探测公网域名时才需要，本机 127.0.0.1 不需要。
    ⚠️ 写在命令行上会被任务计划程序的「操作」里明文保存 —— 更安全的做法是用 -TokenFile。

.PARAMETER TokenFile
    从文件读 token（默认可以指向 tools/cardback-editor/.cardback_token）。
    文件里的空白/换行会被去掉。和 -Token 同时给时，以 -Token 为准。

.PARAMETER TimeoutSec
    单次请求超时，默认 5 秒。

.PARAMETER RestartWaitSec
    重启任务后等几秒再复查一次，默认 8 秒。设 0 表示不复查。

.PARAMETER MaxLogBytes
    日志超过这个大小就滚动成 .1（只保留一份备份），默认 1 MB。

.PARAMETER LogSuccess
    正常时也写一行日志。默认只在异常/恢复时写。

.PARAMETER NoProxy
    绕过系统代理（只在本机 PowerShell 7+ 生效；5.1 会忽略并给一条提示）。
    本机有代理软件（Clash 等）时，127.0.0.1 一般会被自动排除；如果发现
    「明明服务活着却连不上」，加这个开关试试。

.PARAMETER DryRun
    演练模式：照常探测、照常写日志，但绝不重启计划任务。
    ⚠️ 只影响「是否重启」，不影响退出码 —— 异常时仍然是 1。

.EXAMPLE
    # 1) 手工看一眼本机 8765 活着没有
    powershell -NoProfile -ExecutionPolicy Bypass -File .\healthcheck.ps1

.EXAMPLE
    # 2) 演练：查本机，异常时只记日志，不重启
    .\healthcheck.ps1 -DryRun -LogSuccess

.EXAMPLE
    # 3) 定时任务里用：异常时重启 cloudflared 的那个计划任务
    .\healthcheck.ps1 -TaskName "Cardback Cloudflared"

.EXAMPLE
    # 4) 验收公网入口（token 从文件读，不上命令行）
    .\healthcheck.ps1 -Url https://anki.ouquansdiary.cn/health `
                      -TokenFile .\.cardback_token -ExpectVersion 1.4.7

.NOTES
    退出码：0 = 正常 / 活着但没 /health；1 = 异常（已尝试重启或未配置重启）；2 = 参数/环境错误。
    本脚本只读探测，不改任何文件（除了自己的日志）。
    写于 2026-11-09（1.4.7 A4）。**尚未在真机上运行验证**，首次使用请带 -DryRun。
#>
[CmdletBinding()]
param(
    [string]$Url          = "http://127.0.0.1:8765/health",
    [string]$LogPath      = "",
    [string]$TaskName     = "",
    [string]$FallbackUrl  = "http://127.0.0.1:8765/cardback.json",
    [string]$ExpectVersion = "",
    [string]$Token        = "",
    [string]$TokenFile    = "",
    [int]$TimeoutSec      = 5,
    [int]$RestartWaitSec  = 8,
    [int]$MaxLogBytes     = 1048576,
    [switch]$LogSuccess,
    [switch]$NoProxy,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------- 参数收尾

if ([string]::IsNullOrWhiteSpace($LogPath)) {
    $LogPath = Join-Path -Path $PSScriptRoot -ChildPath "healthcheck.log"
}
if ($TimeoutSec -lt 1) { $TimeoutSec = 1 }

# token：-Token 优先，其次 -TokenFile。读失败不致命（查本机本来就不需要 token）。
if (-not $Token -and $TokenFile) {
    try {
        $Token = (Get-Content -Path $TokenFile -Raw -ErrorAction Stop).Trim()
    } catch {
        $Token = ""
        Write-Host ("[WARN] 读不到 token 文件：{0}" -f $_.Exception.Message)
    }
}

# 任何要写进日志/屏幕的文本都过一遍，确保 token 不被记下来。
function Hide-Secret {
    param([string]$Text)
    if (-not $Text) { return "" }
    if ($Token) { return $Text.Replace($Token, "***") }
    return $Text
}

# ---------------------------------------------------------------- 日志

function Write-HealthLog {
    param([string]$Level, [string]$Message)

    $stamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $line  = "{0} [{1}] {2}" -f $stamp, $Level, (Hide-Secret $Message)

    try {
        $dir = Split-Path -Parent $LogPath
        if ($dir -and -not (Test-Path -LiteralPath $dir)) {
            New-Item -ItemType Directory -Path $dir -Force | Out-Null
        }
        # 简单滚动：超过上限就把当前日志改名成 .1（旧的 .1 直接覆盖）
        if ((Test-Path -LiteralPath $LogPath) -and $MaxLogBytes -gt 0) {
            $size = (Get-Item -LiteralPath $LogPath).Length
            if ($size -gt $MaxLogBytes) {
                $bak = $LogPath + ".1"
                if (Test-Path -LiteralPath $bak) { Remove-Item -LiteralPath $bak -Force -ErrorAction SilentlyContinue }
                Move-Item -LiteralPath $LogPath -Destination $bak -Force
            }
        }
        Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
    } catch {
        Write-Host ("[WARN] 写日志失败：{0}" -f $_.Exception.Message)
    }

    Write-Host $line
}

# ---------------------------------------------------------------- HTTP 探测

# 从异常里把响应体挖出来（PS 5.1 和 PS 7 的对象形状不一样，两套都试）。
function Get-ErrorBody {
    param($ErrorRecord)

    try {
        if ($ErrorRecord.ErrorDetails -and $ErrorRecord.ErrorDetails.Message) {
            return [string]$ErrorRecord.ErrorDetails.Message
        }
    } catch { }

    $resp = $null
    try { $resp = $ErrorRecord.Exception.Response } catch { }
    if ($null -eq $resp) { return "" }

    # PS 7：HttpResponseMessage
    try {
        if ($resp.Content -and $resp.Content.ReadAsStringAsync) {
            return [string]$resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
    } catch { }

    # PS 5.1：HttpWebResponse
    try {
        $stream = $resp.GetResponseStream()
        if ($stream) {
            $reader = New-Object System.IO.StreamReader($stream)
            try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
        }
    } catch { }

    return ""
}

# 返回对象：Status(0=连不上) / Body / Server 头 / Error 文本
function Invoke-Probe {
    param([string]$TargetUrl)

    $result = [pscustomobject]@{
        Status = 0
        Body   = ""
        Server = ""
        Error  = ""
    }

    $splat = @{
        Uri             = $TargetUrl
        TimeoutSec      = $TimeoutSec
        UseBasicParsing = $true
        ErrorAction     = "Stop"
    }
    if ($Token) { $splat.Headers = @{ "X-Cardback-Token" = $Token } }
    if ($NoProxy) {
        if ($PSVersionTable.PSVersion.Major -ge 6) {
            $splat.NoProxy = $true
        } else {
            Write-Host "[WARN] 当前是 Windows PowerShell 5.1，-NoProxy 不生效（可换 pwsh 7 再试）"
        }
    }

    try {
        $resp = Invoke-WebRequest @splat
        $result.Status = [int]$resp.StatusCode
        try { $result.Body = [string]$resp.Content } catch { $result.Body = "" }
        try { $result.Server = [string]$resp.Headers["Server"] } catch { $result.Server = "" }
    } catch {
        $result.Error = [string]$_.Exception.Message
        $status = 0
        try {
            if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        } catch { $status = 0 }
        $result.Status = $status
        $result.Body   = Get-ErrorBody $_
    }

    return $result
}

function ConvertFrom-JsonQuiet {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    try { return ($Text | ConvertFrom-Json) } catch { return $null }
}

function Get-BodySnippet {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return "(空)" }
    $flat = ($Text -replace "\s+", " ").Trim()
    if ($flat.Length -gt 160) { return $flat.Substring(0, 160) + "…" }
    return $flat
}

# ---------------------------------------------------------------- 主流程

Write-HealthLog "INFO" ("探测 {0}" -f $Url)

$probe   = Invoke-Probe $Url
$verdict = "unreachable"
$detail  = ""
$version = ""

if ($probe.Status -eq 200) {
    $json = ConvertFrom-JsonQuiet $probe.Body
    if ($null -eq $json) {
        # 200 但不是 JSON：多半是代理/门户页/被别的东西占了端口
        $verdict = "not-json"
        $detail  = "返回 200 但不是 JSON；内容：" + (Get-BodySnippet $probe.Body)
    } elseif ($json.ok -ne $true) {
        $verdict = "not-ok"
        $detail  = ("ok={0} error={1} hint={2}" -f $json.ok, $json.error, $json.hint)
    } else {
        # 1.4.7 的 /health 给了两个版本字段：version="1.4.2"、server="CardbackEditor/1.4.2"。
        # -ExpectVersion 两个都认，写成 "1.4.2" 或 "CardbackEditor/1.4.2" 都行。
        $version   = [string]$json.version
        $serverTag = [string]$json.server
        $verdict   = "healthy"
        $verMatch  = $true
        if ($ExpectVersion) {
            # 三个都试：精确 version、精确 server、server 的 "产品/版本" 后缀
            $verMatch = ($version -eq $ExpectVersion) -or ($serverTag -eq $ExpectVersion) -or ($serverTag -like ("*/" + $ExpectVersion))
        }
        if (-not $verMatch) {
            $verdict = "version-mismatch"
            $detail  = ("期望 version={0}，实际 version={1} / server={2}（旧进程？改过 server.py 没重启？）" -f $ExpectVersion, $version, $serverTag)
        } elseif (-not $version -and -not $serverTag) {
            $detail = "健康，但 /health 没给 version/server 字段"
        }
    }
} elseif ($probe.Status -eq 404) {
    # 1.4.6.2 及更早没有 /health；退回「端口/进程是否活着」的判断
    if ([string]::IsNullOrWhiteSpace($FallbackUrl)) {
        $verdict = "missing-health"
        $detail  = "404 且没配 -FallbackUrl，无法区分「旧版」和「路径写错」"
    } else {
        $fb = Invoke-Probe $FallbackUrl
        if ($fb.Status -eq 200) {
            $verdict = "old-server"
            $detail  = ("没有 /health，但 {0} 返回 200 → 进程活着，是旧版" -f $FallbackUrl)
        } elseif ($fb.Status -eq 401) {
            $verdict = "old-server"
            $detail  = ("没有 /health，退路返回 401 → 进程活着且鉴权在跑；可能是走了隧道/没带 token")
        } elseif ($fb.Status -ge 400) {
            # 4xx/5xx 也说明「端口上确实有个 HTTP 服务在回答」，只是这条路径不通
            # （例如 published/cardback.json 还没生成 → 404）。不能据此判定「服务挂了」。
            $verdict = "old-server"
            $detail  = ("没有 /health；退路 {0} 返回 {1} → 有 HTTP 响应，进程活着" -f $FallbackUrl, $fb.Status)
        } else {
            $verdict = "down"
            $detail  = ("没有 /health，退路 {0} 也失败（状态={1} {2}）" -f $FallbackUrl, $fb.Status, $fb.Error)
        }
    }
} elseif ($probe.Status -eq 401) {
    # /health 和别的接口走同一套 _guard：loopback 且没有 CF-Connecting-IP 才免票。
    # 所以从公网域名探测必然 401 —— 那是「活着但要票」，不是故障。
    if ($Token) {
        $verdict = "bad-token"
        $detail  = "401：带了 token 仍被拒 → token 不对/过期/只换了一端（见 runbook §8）"
    } else {
        $verdict = "auth-required"
        $detail  = "401：服务活着，但这条路径要 token（查域名时加 -Token/-TokenFile；只差这一步）"
    }
} elseif ($probe.Status -eq 429) {
    $verdict = "rate-limited"
    $detail  = "429：被限流，等一个窗口再试（这种情况重启没用，别急着重启）"
} elseif ($probe.Status -eq 0) {
    $verdict = "unreachable"
    $detail  = "连不上：服务没起 / 端口不对 / 隧道没起 / 被代理拦了。" + $probe.Error
} else {
    $verdict = "http-error"
    $detail  = ("状态={0} {1}；内容：{2}" -f $probe.Status, $probe.Error, (Get-BodySnippet $probe.Body))
}

# auth-required = 服务活着、只是这条路径要票（公网探测正常现象），算健康但记 WARN。
$healthy = ($verdict -eq "healthy") -or ($verdict -eq "old-server") -or ($verdict -eq "auth-required")

# 只有「进程真挂了 / 版本对不上」才值得重启。
# 401/429/4xx 重启治不了，而且会打断正在同步的手机 —— 踩过「8765 别乱重启」的坑。
$restartable = @("down", "unreachable", "not-json", "not-ok", "version-mismatch") -contains $verdict

if ($verdict -eq "old-server") {
    Write-HealthLog "WARN" ("服务活着，但缺少 /health（旧版 server.py）：{0}" -f $detail)
} elseif ($verdict -eq "auth-required") {
    Write-HealthLog "WARN" ("服务活着，但探测没带 token：{0}" -f $detail)
} elseif ($healthy) {
    if ($LogSuccess) { Write-HealthLog "OK" ("正常{0}{1}" -f $(if ($version) { "，version=" + $version } else { "" }), $(if ($detail) { "；" + $detail } else { "" })) }
} else {
    Write-HealthLog "ERROR" ("异常（{0}）：{1}" -f $verdict, $detail)
}

# ---------------------------------------------------------------- 异常处理（可选重启）

if ($restartable) {
    if (-not $TaskName) {
        Write-HealthLog "INFO" "没给 -TaskName：只报警，不重启（推荐先这样观察几天）"
    } elseif ($DryRun) {
        Write-HealthLog "WARN" ("[DryRun] 本应重启计划任务「{0}」，演练模式跳过" -f $TaskName)
    } else {
        # Restart-ScheduledTaskInstance 要求任务正在运行；没在跑就退回 Start-ScheduledTask。
        $restarted = $false
        try {
            Restart-ScheduledTaskInstance -TaskName $TaskName -ErrorAction Stop
            $restarted = $true
            Write-HealthLog "WARN" ("已重启计划任务「{0}」（restart）" -f $TaskName)
        } catch {
            Write-HealthLog "INFO" ("restart 失败（任务可能没在运行）：{0}" -f $_.Exception.Message)
        }
        if (-not $restarted) {
            try {
                Start-ScheduledTask -TaskName $TaskName -ErrorAction Stop
                $restarted = $true
                Write-HealthLog "WARN" ("已启动计划任务「{0}」（start）" -f $TaskName)
            } catch {
                Write-HealthLog "ERROR" ("重启计划任务「{0}」失败：{1}" -f $TaskName, $_.Exception.Message)
            }
        }

        if ($restarted -and $RestartWaitSec -gt 0) {
            Write-HealthLog "INFO" ("等 {0} 秒后复查…" -f $RestartWaitSec)
            Start-Sleep -Seconds $RestartWaitSec
            $again = Invoke-Probe $Url
            if ($again.Status -eq 200) {
                $againJson = ConvertFrom-JsonQuiet $again.Body
                if ($againJson -and $againJson.ok -eq $true) {
                    Write-HealthLog "OK" ("重启后恢复正常，version={0}" -f ([string]$againJson.version))
                } else {
                    Write-HealthLog "WARN" ("重启后有响应但 ok≠true；内容：" + (Get-BodySnippet $again.Body))
                }
            } elseif ($again.Status -eq 404) {
                Write-HealthLog "WARN" "重启后有响应但仍是 404（没有 /health）"
            } else {
                Write-HealthLog "ERROR" ("重启后仍然异常：状态={0} {1}" -f $again.Status, $again.Error)
            }
        }
    }
} elseif (-not $healthy) {
    Write-HealthLog "INFO" ("（{0}）不属于「进程挂了」，按策略不重启；先按 runbook §7 故障表定位" -f $verdict)
}

# ---------------------------------------------------------------- 退出码

if ($healthy) {
    $conclusion = switch ($verdict) {
        "old-server"    { "活着（旧版，无 /health）" }
        "auth-required" { "活着（要 token 才能看版本；加 -Token 再探一次即可确认版本）" }
        default         { "正常" }
    }
    Write-Host ("结论：{0}" -f $conclusion)
    exit 0
}

Write-Host ("结论：异常（{0}）" -f $verdict)
exit 1
