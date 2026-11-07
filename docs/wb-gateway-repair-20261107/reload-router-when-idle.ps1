# Incident-scoped reload. Only the identified old router may be stopped.
[CmdletBinding()]
param([switch]$Execute, [ValidateRange(10,1200)][int]$WaitBudgetSeconds = 600, [int]$ExpectedRouterPid = 10576)
$ErrorActionPreference = 'Stop'
$reloadRoot = 'D:/deepseek_v4.1flash1017/workbuddy2api-panel'
$reloadRouter = Join-Path $reloadRoot 'run/wb-router.js'
$reloadExpectedHash = '2262451f13e4dcef160bb133d1fd17754aa56f0a4ace45c069ea0ebf59c7ce0f'
$reloadEvidence = [ordered]@{ startedAt=(Get-Date).ToString('o'); execute=[bool]$Execute; productionInferenceRequests=0; oldRouterPid=$ExpectedRouterPid; stoppedRouter=$false; observations=@() }
function Get-ReloadActivity([int]$Port, [string]$Key) {
    $response=$null; $stream=$null; $memory=$null; $request=$null
    $clock=[Diagnostics.Stopwatch]::StartNew()
    try {
        $request=[Net.HttpWebRequest][Net.WebRequest]::Create(('http://127.0.0.1:{0}/status' -f $Port))
        $request.Proxy=$null; $request.Method='GET'; $request.AllowAutoRedirect=$false
        $request.KeepAlive=$false; $request.Timeout=3000; $request.ReadWriteTimeout=3000
        $request.MaximumResponseHeadersLength=16; $request.Headers['Authorization']='Bearer '+$Key
        $response=[Net.HttpWebResponse]$request.GetResponse()
        if ([int]$response.StatusCode -ne 200 -or $response.ContentLength -gt 1048576) { throw 'pool-status-rejected' }
        $stream=$response.GetResponseStream(); $memory=New-Object IO.MemoryStream; $buffer=New-Object byte[] 4096
        while ($true) {
            $remaining=3000-[int]$clock.ElapsedMilliseconds
            if ($remaining -le 0) { throw 'pool-status-timeout' }
            $stream.ReadTimeout=$remaining; $received=$stream.Read($buffer,0,$buffer.Length)
            if ($received -eq 0) { break }
            $memory.Write($buffer,0,$received)
            if ($memory.Length -gt 1048576) { throw 'pool-status-too-large' }
        }
        $json=ConvertFrom-Json ([Text.Encoding]::UTF8.GetString($memory.ToArray())) -ErrorAction Stop
        if ($json.accounts -isnot [array] -or @($json.accounts).Count -lt 1 -or -not (Test-WbCount $json.healthy -Positive)) { throw 'pool-status-invalid' }
        $active=0
        foreach ($account in @($json.accounts)) {
            if ($null -eq $account.in_flight -or -not (Test-WbCount $account.in_flight)) { throw 'pool-status-invalid-in-flight' }
            $active += [int]$account.in_flight
        }
        return [pscustomobject]@{ Port=$Port; Healthy=[int]$json.healthy; InFlight=$active; Accounts=@($json.accounts).Count }
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }; if ($null -ne $memory) { $memory.Dispose() }
        if ($null -ne $response) { $response.Close() }; if ($null -ne $request) { $request.Abort() }; $clock.Stop()
    }
}
function Get-ReloadProtectedPools {
    $rows=@(Get-CimInstance Win32_Process -Filter 'ProcessId = 38084 OR ProcessId = 29624' -ErrorAction Stop)
    if ($rows.Count -ne 2) { throw 'protected-pool-pid-changed' }
    foreach ($row in $rows) {
        $expected=if ($row.ProcessId -eq 38084) { Join-Path $reloadRoot 'run/wb2api.exe' } else { Join-Path $reloadRoot 'run-bot/wb2api.exe' }
        if (-not (Test-WbExactPath $row.ExecutablePath $expected)) { throw 'protected-pool-identity-changed' }
    }
    return @($rows | Sort-Object ProcessId | ForEach-Object { [pscustomobject]@{ Pid=[int]$_.ProcessId; Created=([datetime]$_.CreationDate).ToUniversalTime().ToString('o'); Path=$_.ExecutablePath } })
}
try {
    if (-not $Execute) { Write-Output 'PLAN ONLY: after installation and independent testing, -Execute waits for two idle samples and reloads only the verified old router.'; exit 0 }
    if ((Get-FileHash -LiteralPath $reloadRouter -Algorithm SHA256).Hash.ToLowerInvariant() -ne $reloadExpectedHash) { throw 'installed-router-hash-not-approved' }
    . (Join-Path $reloadRoot 'run/wb-service-check.ps1') -Library
    $reloadDefinition=@(Get-WbServiceDefinitions $reloadRoot | Where-Object { $_.Name -eq 'router' })[0]
    $reloadSnapshot=Get-WbListenerSnapshot 8788
    if (-not $reloadSnapshot.Success -or @($reloadSnapshot.Owners).Count -ne 1) { throw 'router-owner-inspection-failed' }
    $reloadOwner=@($reloadSnapshot.Owners)[0]
    if ($reloadOwner.ProcessId -ne $ExpectedRouterPid -or -not (Test-WbOwnerIdentity $reloadDefinition $reloadOwner)) { throw 'router-owner-not-approved' }
    $reloadExactOwner=Get-CimInstance Win32_Process -Filter ('ProcessId = '+$ExpectedRouterPid) -ErrorAction Stop
    if (-not (Test-WbOwnerIdentity $reloadDefinition $reloadExactOwner) -or [string]$reloadExactOwner.CreationDate -cne [string]$reloadOwner.CreationDate -or $reloadExactOwner.CommandLine -cne $reloadOwner.CommandLine) { throw 'router-owner-changed-before-handle' }
    $reloadManaged=Get-Process -Id $ExpectedRouterPid -ErrorAction Stop
    $null=$reloadManaged.Handle
    $reloadConfirmedOwner=Get-CimInstance Win32_Process -Filter ('ProcessId = '+$ExpectedRouterPid) -ErrorAction Stop
    if (-not (Test-WbOwnerIdentity $reloadDefinition $reloadConfirmedOwner) -or ([datetime]$reloadConfirmedOwner.CreationDate).ToUniversalTime().Ticks -ne ([datetime]$reloadExactOwner.CreationDate).ToUniversalTime().Ticks -or $reloadConfirmedOwner.CommandLine -cne $reloadExactOwner.CommandLine) { throw 'router-owner-changed-during-handle-binding' }
    if ([Math]::Abs(($reloadManaged.StartTime.ToUniversalTime()-([datetime]$reloadConfirmedOwner.CreationDate).ToUniversalTime()).TotalMilliseconds) -gt 1) { throw 'router-creation-time-mismatch' }
    $reloadEvidence.protectedPoolsBefore=@(Get-ReloadProtectedPools)
    $reloadConfig=ConvertFrom-Json ([IO.File]::ReadAllText((Join-Path $reloadRoot 'run/router.config.json')).TrimStart([char]0xFEFF))
    $reloadMainKey=if ($reloadConfig.main.key) { [string]$reloadConfig.main.key } else { 'sk-wb-local-8788' }
    $reloadAuxKey=if ($reloadConfig.aux.key) { [string]$reloadConfig.aux.key } else { 'sk-wb-local-8789' }
    $reloadClock=[Diagnostics.Stopwatch]::StartNew(); $reloadIdleSamples=0
    do {
        $activity=@(Get-ReloadActivity 8790 $reloadMainKey; Get-ReloadActivity 8789 $reloadAuxKey)
        $reloadEvidence.observations += [pscustomobject]@{ At=(Get-Date).ToString('o'); Pools=$activity }
        if (@($activity | Where-Object { $_.InFlight -ne 0 }).Count -eq 0) { $reloadIdleSamples++ } else { $reloadIdleSamples=0 }
        Write-Output ('WAIT main={0} aux={1} idleSamples={2}' -f $activity[0].InFlight,$activity[1].InFlight,$reloadIdleSamples)
        if ($reloadIdleSamples -ge 2) {
            $reloadBeforeKill=Get-CimInstance Win32_Process -Filter ('ProcessId = '+$ExpectedRouterPid) -ErrorAction Stop
            if (-not (Test-WbOwnerIdentity $reloadDefinition $reloadBeforeKill) -or ([datetime]$reloadBeforeKill.CreationDate).ToUniversalTime().Ticks -ne ([datetime]$reloadConfirmedOwner.CreationDate).ToUniversalTime().Ticks -or $reloadBeforeKill.CommandLine -cne $reloadConfirmedOwner.CommandLine) { throw 'router-owner-changed-before-stop' }
            $reloadLastActivity=@(Get-ReloadActivity 8790 $reloadMainKey; Get-ReloadActivity 8789 $reloadAuxKey)
            $reloadEvidence.observations += [pscustomobject]@{ At=(Get-Date).ToString('o'); Pools=$reloadLastActivity; FinalCheck=$true }
            if (@($reloadLastActivity | Where-Object { $_.InFlight -ne 0 }).Count -ne 0) { $reloadIdleSamples=0; continue }
            break
        }
        Start-Sleep -Milliseconds 1500
    } while ($reloadClock.Elapsed.TotalSeconds -lt $WaitBudgetSeconds)
    if ($reloadIdleSamples -lt 2) { throw 'no-idle-window-within-budget-no-process-stopped' }
    if ($reloadManaged.HasExited) { throw 'old-router-already-exited-no-process-stopped' }
    if ((Get-FileHash -LiteralPath $reloadRouter -Algorithm SHA256).Hash.ToLowerInvariant() -ne $reloadExpectedHash) { throw 'router-file-changed-before-reload' }
    $reloadEvidence.stopRequestedAt=(Get-Date).ToString('o')
    $reloadManaged.Kill()
    if (-not $reloadManaged.WaitForExit(5000) -or -not $reloadManaged.HasExited) { throw 'router-stop-not-confirmed-no-starter-run' }
    $reloadEvidence.stoppedAt=(Get-Date).ToString('o'); $reloadEvidence.stoppedRouter=$true
    Start-Process -FilePath 'wscript.exe' -ArgumentList @('//B',('"'+(Join-Path $reloadRoot 'run/start-router.vbs')+'"')) -WorkingDirectory (Join-Path $reloadRoot 'run') -ErrorAction Stop | Out-Null
    $reloadReadyClock=[Diagnostics.Stopwatch]::StartNew(); $reloadReady=$false
    do {
        $reloadStatus=Invoke-WbBoundedJsonGet 'http://127.0.0.1:8788/_router/status' 2000 32768
        $reloadReady=$reloadStatus.Ok -and $reloadStatus.Json.main.service -eq 'workbuddy2api' -and $reloadStatus.Json.main.healthy -eq $true -and $reloadStatus.Json.aux.service -eq 'workbuddy2api' -and $reloadStatus.Json.aux.healthy -eq $true
        if ($reloadReady) { break }
        Start-Sleep -Milliseconds 250
    } while ($reloadReadyClock.Elapsed.TotalSeconds -lt 30)
    if (-not $reloadReady) { throw 'router-reload-get-verification-failed' }
    $reloadEvidence.readyAt=(Get-Date).ToString('o'); $reloadEvidence.routerStatus=$reloadStatus.Json
    $reloadEvidence.protectedPoolsAfter=@(Get-ReloadProtectedPools)
    if (($reloadEvidence.protectedPoolsBefore | ConvertTo-Json -Compress) -cne ($reloadEvidence.protectedPoolsAfter | ConvertTo-Json -Compress)) { throw 'protected-pool-process-changed-during-reload' }
    $reloadFinal=Get-WbListenerSnapshot 8788
    if (-not $reloadFinal.Success -or @($reloadFinal.Owners).Count -ne 1 -or -not (Test-WbOwnerIdentity $reloadDefinition @($reloadFinal.Owners)[0])) { throw 'new-router-owner-invalid' }
    $reloadEvidence.newRouterPid=[int]@($reloadFinal.Owners)[0].ProcessId
    $reloadEvidence.success=$true
} catch {
    $reloadEvidence.success=$false
    $reloadEvidence.error=if ($_.Exception.Message -match '^[a-z0-9-]+$') { $_.Exception.Message } else { 'reload-operation-failed-see-state' }
    Write-Output ('RELOAD FAILED reason='+$reloadEvidence.error)
} finally {
    if ($Execute) {
        $reloadEvidence.finishedAt=(Get-Date).ToString('o')
        $outputFile=Join-Path $PSScriptRoot ('router-reload-evidence-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')+'.json')
        $serialized=$reloadEvidence | ConvertTo-Json -Depth 12
        $outputStream=[IO.File]::Open($outputFile,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
        try { $outputBytes=(New-Object Text.UTF8Encoding($false)).GetBytes($serialized+"`n"); $outputStream.Write($outputBytes,0,$outputBytes.Length) } finally { $outputStream.Dispose() }
        Write-Output ('EVIDENCE '+$outputFile)
    }
}
if (-not $reloadEvidence.success) { exit 1 }
Write-Output ('RELOAD OK newRouterPid='+$reloadEvidence.newRouterPid)
