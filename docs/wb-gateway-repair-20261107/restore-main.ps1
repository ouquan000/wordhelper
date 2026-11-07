$ErrorActionPreference = 'Stop'
$expectedStubPid = 32508
$expectedCreatedMs = 1793990543821
$runDir = 'D:\deepseek_v4.1flash1017\workbuddy2api-panel\run'
$expectedMain = Join-Path $runDir 'wb2api.exe'
$stub = Get-CimInstance Win32_Process -Filter "ProcessId=$expectedStubPid"
if (-not $stub -or $stub.Name -ne 'python.exe' -or $stub.CommandLine -notmatch 'C:/temp/stub-rewrite\.py') { throw 'Stub process identity changed; refusing to stop any process.' }
$createdMs = ([DateTimeOffset]$stub.CreationDate.ToUniversalTime()).ToUnixTimeMilliseconds()
if ([math]::Abs($createdMs - $expectedCreatedMs) -gt 1000) { throw 'Stub creation time changed; refusing PID reuse.' }
$listeners = @(Get-NetTCPConnection -State Listen -LocalPort 8790 -ErrorAction SilentlyContinue)
if (-not $listeners -or @($listeners | Where-Object { $_.OwningProcess -ne $expectedStubPid }).Count) { throw 'Port 8790 ownership changed; refusing to stop an unknown listener.' }
$state = Invoke-RestMethod 'http://127.0.0.1:8790/rewrite/status' -TimeoutSec 3
if ($state.queued -ne 0 -or $state.done -ne 0) { throw 'Stub has nonempty temporary state; preserve it before migration.' }
$before = [ordered]@{ at=(Get-Date).ToString('o'); stubPid=$expectedStubPid; stubCreatedMs=$createdMs; command=$stub.CommandLine; temporaryState=$state; action='stop verified empty test stub only' }
Stop-Process -Id $expectedStubPid -ErrorAction Stop
$starter = Join-Path $runDir 'start-silent.vbs'
Start-Process -FilePath 'wscript.exe' -ArgumentList @('//B', ('"' + $starter + '"')) -WorkingDirectory $runDir | Out-Null
$deadline = (Get-Date).AddSeconds(25)
$healthy = $null
while ((Get-Date) -lt $deadline) {
    try {
        $h = Invoke-RestMethod 'http://127.0.0.1:8790/healthz' -TimeoutSec 2
        if ($h.service -eq 'workbuddy2api' -and $h.healthy -gt 0) { $healthy=$h; break }
    } catch {}
    Start-Sleep -Milliseconds 500
}
$owners = @(Get-NetTCPConnection -State Listen -LocalPort 8790 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
$processes = @($owners | ForEach-Object { Get-CimInstance Win32_Process -Filter "ProcessId=$_" } | Select-Object ProcessId,ExecutablePath,CreationDate)
$after = [ordered]@{ at=(Get-Date).ToString('o'); health=$healthy; owners=$processes; originalStubStopped=(-not [bool](Get-Process -Id $expectedStubPid -ErrorAction SilentlyContinue)); stubDefaultPort=18791; stubRestarted=$false }
$result = [ordered]@{ before=$before; after=$after }
$result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'recovery-evidence.json') -Encoding UTF8
$result | ConvertTo-Json -Depth 6
if (-not $healthy -or $processes.Count -ne 1 -or $processes[0].ExecutablePath -ine $expectedMain) { throw 'Main gateway was not verified healthy under the expected executable; inspect evidence, do not kill unknown processes.' }
