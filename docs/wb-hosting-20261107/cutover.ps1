# Cut the three WB services over to scheduled-task hosting, with identity checks first.
# Aborts if any listener does not match the expected executable/command line.
$ErrorActionPreference = 'Stop'
$panel = 'D:\deepseek_v4.1flash1017\workbuddy2api-panel'
$log = 'D:\deepseek harness\work_anki_enligish\docs\wb-hosting-20261107\cutover.log'

function Log($m) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $m" | Tee-Object -FilePath $log -Append }

$targets = @(
    @{ Name = 'main';   Port = 8790; Exe = "$panel\run\wb2api.exe";      Cmd = '-config config.json'; Task = 'WB-MainPool-Boot'; Probe = 'http://127.0.0.1:8790/healthz' },
    @{ Name = 'router'; Port = 8788; Exe = 'C:\Program Files\nodejs\node.exe'; Cmd = "$panel\run\wb-router.js"; Task = 'WB-Router-Boot'; Probe = 'http://127.0.0.1:8788/_router/status' },
    @{ Name = 'aux';    Port = 8789; Exe = "$panel\run-bot\wb2api.exe";  Cmd = '-config config.json'; Task = 'WB-Aux-Boot'; Probe = 'http://127.0.0.1:8789/healthz' }
)

function Get-ListenerPid([int]$port) {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $c) { return $null }
    return [int]$c.OwningProcess
}

function Get-ProcInfo([int]$procId) {
    $p = Get-CimInstance Win32_Process -Filter "ProcessId=$procId"
    if (-not $p) { return $null }
    return $p
}

function Probe-Ok([string]$url) {
    try { $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5; return ($r.StatusCode -eq 200) } catch { return $false }
}

# ---------- step 1: verify identities ----------
$verified = @()
foreach ($t in $targets) {
    $procId = Get-ListenerPid $t.Port
    if (-not $procId) { Log "ABORT: nothing listening on $($t.Port)"; exit 2 }
    $p = Get-ProcInfo $procId
    if (-not $p) { Log "ABORT: cannot read process $procId"; exit 2 }
    $exeOk = ($p.ExecutablePath -eq $t.Exe)
    $cmdOk = ($p.CommandLine -like "*$($t.Cmd)*")
    if (-not ($exeOk -and $cmdOk)) {
        Log "ABORT: port $($t.Port) owned by unexpected pid=$procId exe=$($p.ExecutablePath) cmd=$($p.CommandLine)"
        exit 2
    }
    Log "VERIFIED $($t.Name) port=$($t.Port) pid=$procId"
    $verified += [pscustomobject]@{ Name = $t.Name; Port = $t.Port; Pid = $procId; Task = $t.Task; Probe = $t.Probe; Exe = $t.Exe; Cmd = $t.Cmd }
}

# ---------- step 2: stop verified services ----------
foreach ($v in $verified) {
    Stop-Process -Id $v.Pid -Force -ErrorAction SilentlyContinue
    Log "STOPPED $($v.Name) pid=$($v.Pid)"
}
Start-Sleep -Seconds 2
foreach ($v in $verified) {
    $left = Get-ListenerPid $v.Port
    if ($left) { Log "PORT STILL BUSY $($v.Port) pid=$left" }
}

# ---------- step 3: start via scheduled tasks ----------
foreach ($v in $verified) {
    schtasks /Run /TN $v.Task | Out-Null
    Log "RUN task $($v.Task)"
}

# ---------- step 4: wait for health ----------
$deadline = (Get-Date).AddSeconds(90)
$ok = @{}
while ((Get-Date) -lt $deadline) {
    foreach ($v in $verified) {
        if (-not $ok.ContainsKey($v.Name) -and (Probe-Ok $v.Probe)) { $ok[$v.Name] = $true; Log "HEALTH OK $($v.Name)" }
    }
    if ($ok.Count -eq $verified.Count) { break }
    Start-Sleep -Seconds 2
}

# ---------- step 5: fallback if needed ----------
if ($ok.Count -ne $verified.Count) {
    Log "HEALTH INCOMPLETE ($($ok.Count)/$($verified.Count)) - fallback to direct launchers"
    Start-Process -FilePath "$panel\run\start-silent.cmd" -WorkingDirectory "$panel\run" -WindowStyle Hidden
    Start-Process -FilePath "$panel\run-bot\start-bot.cmd" -WorkingDirectory "$panel\run-bot" -WindowStyle Hidden
    Start-Sleep -Seconds 15
    foreach ($v in $verified) { if (Probe-Ok $v.Probe) { Log "FALLBACK OK $($v.Name)" } else { Log "FALLBACK FAIL $($v.Name)" } }
}

# ---------- step 6: prove the new processes are not under DSH ----------
foreach ($v in $verified) {
    $procId = Get-ListenerPid $v.Port
    if (-not $procId) { Log "POST-CHECK: no listener on $($v.Port)"; continue }
    $chain = @()
    $cur = $procId
    for ($i = 0; $i -lt 6; $i++) {
        $p = Get-ProcInfo $cur
        if (-not $p) { break }
        $chain += "$($p.ProcessId):$($p.Name)"
        if (-not $p.ParentProcessId) { break }
        $cur = [int]$p.ParentProcessId
    }
    $line = ($chain -join ' <- ')
    $underDsh = $line -match 'dsh|node'
    Log "CHAIN $($v.Name) port=$($v.Port): $line"
    if ($underDsh) { Log "WARN $($v.Name) chain still looks DSH-related" }
}

Log 'CUTOVER DONE'
