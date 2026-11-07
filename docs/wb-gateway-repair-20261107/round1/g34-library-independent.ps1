[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$CandidateDir,[Parameter(Mandatory=$true)][string]$Scratch,[Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference = 'Stop'
$global:IndependentCases = New-Object 'System.Collections.Generic.List[object]'
$global:IndependentLogs = New-Object 'System.Collections.Generic.List[string]'
function Check([string]$Name,[bool]$Passed,$Detail) {
    $global:IndependentCases.Add([pscustomobject]@{name=$Name;passed=$Passed;detail=$Detail})
    if (-not $Passed) { Write-Output ('FAIL ' + $Name) }
}
function Snapshot($Owner) { return [pscustomobject]@{Success=$true;Owners=@($Owner);Reason=''} }
function JsonResult($Value) { return [pscustomobject]@{Ok=$true;StatusCode=200;Json=$Value;Reason='http-json-ok'} }
. (Join-Path $CandidateDir 'wb-service-check.ps1') -Library
. (Join-Path $CandidateDir 'wb-gateway-guard.ps1') -Library
$specimenRoot = Join-Path $Scratch 'mock panel with spaces'
$defs = @(Get-WbServiceDefinitions $specimenRoot)
$healthyJson = [pscustomobject]@{service='workbuddy2api';healthy=7}
$routerJson = [pscustomobject]@{state='offline';qq=[pscustomobject]@{};aux=[pscustomobject]@{target='127.0.0.1:8789';healthy=$true;paused=$false};hold=[pscustomobject]@{onlineMs=0;offlineMs=0};weight=[pscustomobject]@{main=5;aux=3};counters=[pscustomobject]@{requests=0;toMain=0;toAux=0};main=[pscustomobject]@{target='127.0.0.1:8790';healthy=$true};mainOk=$true;auxOk=$true}
Check 'definitions-cover-three-distinct-services' (($defs.Name -join ',') -eq 'main,aux,router' -and ($defs.Port -join ',') -eq '8790,8789,8788') $defs
foreach ($definition in $defs) {
    $exe = $definition.Executable
    if ($definition.Name -eq 'router') { $exe = 'C:\Program Files\nodejs\node.exe' }
    $owner = [pscustomobject]@{ProcessId=81234;ExecutablePath=$exe;CommandLine=('"' + $exe + '" "' + $definition.Script + '"');CreationDate='independent-stamp'}
    $json = $healthyJson
    if ($definition.Name -eq 'router') { $json = $routerJson }
    $foreign = [pscustomobject]@{ProcessId=81234;ExecutablePath=(Join-Path $Scratch 'foreign\wb2api.exe');CommandLine='"C:\foreign\app.exe"';CreationDate='independent-stamp'}
    foreach ($stateName in @('healthy','unhealthy','foreign','missing','error')) {
        $snap = Snapshot $owner
        $http = JsonResult $json
        if ($stateName -eq 'unhealthy') { $http = [pscustomobject]@{Ok=$false;StatusCode=404;Json=$null;Reason='http-404'} }
        if ($stateName -eq 'foreign') { $snap = Snapshot $foreign }
        if ($stateName -eq 'missing') { $snap = [pscustomobject]@{Success=$true;Owners=@();Reason=''} }
        if ($stateName -eq 'error') { $snap = [pscustomobject]@{Success=$false;Owners=@();Reason='inspection-failed'} }
        $resolved = Resolve-WbServiceState $definition $snap $http
        $codeMap = @{healthy=0;unhealthy=30;foreign=20;missing=10;error=40}
        Check ('resolve-' + $definition.Name + '-' + $stateName) ($resolved.State -eq $stateName -and $resolved.ExitCode -eq $codeMap[$stateName]) $resolved
        $global:IndependentStartCalls = 0
        $global:IndependentWaitCalls = 0
        $global:IndependentCurrentState = $resolved
        $checked = {param($d) return $global:IndependentCurrentState}
        $logged = {param($message) $global:IndependentLogs.Add($message)}
        $started = {param($d) $global:IndependentStartCalls++}
        $waited = {param($d) $global:IndependentWaitCalls++; return $global:IndependentCurrentState}
        $notHeld = {param($d) return $false}
        if ($stateName -ne 'missing') {
            $answer = Invoke-WbGuardService $definition 'ok' $false $checked $notHeld $started $waited $logged
            Check ('guard-' + $definition.Name + '-' + $stateName + '-no-start-or-wait') ($answer.ExitCode -eq $codeMap[$stateName] -and $answer.Action -eq 'none' -and $global:IndependentStartCalls -eq 0 -and $global:IndependentWaitCalls -eq 0) $answer
            if ($stateName -eq 'foreign') { Check ('guard-' + $definition.Name + '-foreign-explicit-conflict') (($global:IndependentLogs | Where-Object {$_ -match ('CONFLICT.*' + $definition.Name) }).Count -gt 0) @($global:IndependentLogs) }
        } else {
            $answer = Invoke-WbGuardService $definition 'ok' $true $checked $notHeld $started $waited $logged
            Check ('guard-' + $definition.Name + '-missing-NoRestart') ($answer.ExitCode -eq 10 -and $answer.Action -eq 'no-restart' -and $global:IndependentStartCalls -eq 0) $answer
            $isHeld = {param($d) return $true}
            $answer = Invoke-WbGuardService $definition 'ok' $false $checked $isHeld $started $waited $logged
            Check ('guard-' + $definition.Name + '-missing-hold') ($answer.ExitCode -eq 10 -and $answer.Action -eq 'held' -and $global:IndependentStartCalls -eq 0) $answer
            $answer = Invoke-WbGuardService $definition 'broken' $false $checked $notHeld $started $waited $logged
            Check ('guard-' + $definition.Name + '-missing-bad-config-blocked') ($answer.ExitCode -eq 40 -and $answer.Action -eq 'config-blocked' -and $global:IndependentStartCalls -eq 0) $answer
            $global:IndependentProbeCalls = 0
            $global:IndependentForeign = New-WbServiceState $definition 'foreign' 'CONFLICT-listener-owner-identity-mismatch' @($foreign)
            $raceCheck = {param($d) $global:IndependentProbeCalls++; if ($global:IndependentProbeCalls -eq 1) {return $global:IndependentCurrentState}; return $global:IndependentForeign}
            $answer = Invoke-WbGuardService $definition 'ok' $false $raceCheck $notHeld $started $waited $logged
            Check ('guard-' + $definition.Name + '-missing-to-foreign-race') ($answer.ExitCode -eq 20 -and $answer.Action -eq 'changed-before-start' -and $global:IndependentStartCalls -eq 0) $answer
            $global:IndependentRecovered = New-WbServiceState $definition 'healthy' 'independent-recovered' @($owner)
            $recoveredWait = {param($d) $global:IndependentWaitCalls++; return $global:IndependentRecovered}
            $answer = Invoke-WbGuardService $definition 'ok' $false $checked $notHeld $started $recoveredWait $logged
            Check ('guard-' + $definition.Name + '-missing-recovered') ($answer.ExitCode -eq 0 -and $answer.Action -eq 'recovered' -and $global:IndependentStartCalls -eq 1 -and $global:IndependentWaitCalls -eq 1) $answer
            $throwStart = {param($d) throw 'independent-start-failed'}
            $answer = Invoke-WbGuardService $definition 'ok' $false $checked $notHeld $throwStart $recoveredWait $logged
            Check ('guard-' + $definition.Name + '-start-failure-nonzero') ($answer.ExitCode -eq 40 -and $answer.Action -eq 'start-failed') $answer
        }
    }
    $global:IndependentOwner = $owner
    $global:IndependentHttpCount = 0
    $global:IndependentListenerCount = 0
    $stableProvider = {param($port) $global:IndependentListenerCount++; return (Snapshot $global:IndependentOwner)}
    $goodProvider = {param($uri,$timeout,$maximum) $global:IndependentHttpCount++; return (JsonResult $global:IndependentGoodJson)}
    $global:IndependentGoodJson = $json
    $answer = Get-WbServiceState $definition 300 4096 $stableProvider $goodProvider
    Check ('service-' + $definition.Name + '-two-snapshots-one-http') ($answer.State -eq 'healthy' -and $global:IndependentListenerCount -eq 2 -and $global:IndependentHttpCount -eq 1) $answer
    $global:IndependentListenerCount = 0
    $unstable = {param($port) $global:IndependentListenerCount++; $o = $global:IndependentOwner.PSObject.Copy(); if ($global:IndependentListenerCount -gt 1) {$o.CreationDate='reused-pid-new-process'}; return (Snapshot $o)}
    $answer = Get-WbServiceState $definition 300 4096 $unstable $goodProvider
    Check ('service-' + $definition.Name + '-same-pid-owner-stamp-change-fails') ($answer.ExitCode -eq 40 -and $answer.Reason -eq 'listener-owner-changed-during-http-check') $answer
    $global:IndependentOwner = $foreign
    $global:IndependentHttpCount = 0
    $answer = Get-WbServiceState $definition 300 4096 $stableProvider $goodProvider
    Check ('service-' + $definition.Name + '-foreign-never-http-probed') ($answer.State -eq 'foreign' -and $global:IndependentHttpCount -eq 0) $answer
}
foreach ($value in @(0,-1,1.5,'7',$true,$false,$null)) {
    $answer = Test-WbHealthJson ([pscustomobject]@{service='workbuddy2api';healthy=$value})
    Check ('health-reject-' + $(if($null -eq $value){'null'}else{$value.GetType().Name + '-' + $value})) (-not $answer.Ok) $answer
}
foreach ($badHealthService in @('fake','WorkBuddy2API','workbuddy2api ')) {
    $answer = Test-WbHealthJson ([pscustomobject]@{service=$badHealthService;healthy=7})
    Check ('health-reject-service-' + $badHealthService) (-not $answer.Ok) $answer
}
$answer = Test-WbHealthJson $healthyJson
Check 'health-valid-positive-service' $answer.Ok $answer
$routerOwner = [pscustomobject]@{ProcessId=99;ExecutablePath='C:\Program Files\nodejs\node.exe';CommandLine=('"C:\Program Files\nodejs\node.exe" "' + $defs[2].Script + '"');CreationDate='a'}
Check 'router-exact-owner-with-space-path' (Test-WbOwnerIdentity $defs[2] $routerOwner) $routerOwner
foreach ($argv in @(
    ('"C:\Program Files\nodejs\node.exe" -e "' + $defs[2].Script + '"'),
    ('"C:\Program Files\nodejs\node.exe" "C:\foreign\entry.js" "' + $defs[2].Script + '"'),
    ('"C:\Program Files\nodejs\node.exe" "' + $defs[2].Script + '.evil"'),
    ('"C:\Program Files\nodejs\node.exe" "' + $defs[2].Script))) {
    $spoof = $routerOwner.PSObject.Copy(); $spoof.CommandLine=$argv
    Check 'router-reject-commandline-spoof' (-not (Test-WbOwnerIdentity $defs[2] $spoof)) $spoof
}
$watchPath = Join-Path $Scratch 'watcher with spaces.ps1'
[IO.File]::WriteAllText($watchPath, '# independent watcher fixture')
$watchOwner = [pscustomobject]@{ProcessId=13;ExecutablePath='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe';CommandLine=('powershell.exe -NoProfile -File "' + $watchPath + '"')}
Check 'watcher-exact-file-owner' (Test-WbWatchOwner $watchOwner $watchPath) $watchOwner
foreach ($args in @('-Command','-EncodedCommand','-c','-e','-ec')) {
    $bad = $watchOwner.PSObject.Copy(); $bad.CommandLine=('powershell.exe ' + $args + ' "' + $watchPath + '"')
    Check ('watcher-reject-eval-' + $args) (-not (Test-WbWatchOwner $bad $watchPath)) $bad
}
$global:IndependentWatcherStarts=0
$watchStart = {$global:IndependentWatcherStarts++}
$watchLog = {param($message) $global:IndependentLogs.Add($message)}
$missingWatch = {[pscustomobject]@{State='missing';ProcessId=0}}
$goodWatch = {[pscustomobject]@{State='healthy';ProcessId=13}}
$badWatch = {[pscustomobject]@{State='error';ProcessId=0}}
Check 'watch-NoRestart-no-launch' ((Invoke-WbGuardWatch $watchPath $true $missingWatch $watchStart $watchLog) -eq 10 -and $global:IndependentWatcherStarts -eq 0) $global:IndependentWatcherStarts
Check 'watch-healthy-no-launch' ((Invoke-WbGuardWatch $watchPath $false $goodWatch $watchStart $watchLog) -eq 0 -and $global:IndependentWatcherStarts -eq 0) $global:IndependentWatcherStarts
Check 'watch-inspection-error-no-launch' ((Invoke-WbGuardWatch $watchPath $false $badWatch $watchStart $watchLog) -eq 40 -and $global:IndependentWatcherStarts -eq 0) $global:IndependentWatcherStarts
Check 'watch-missing-launch-once' ((Invoke-WbGuardWatch $watchPath $false $missingWatch $watchStart $watchLog) -eq 0 -and $global:IndependentWatcherStarts -eq 1) $global:IndependentWatcherStarts
$configDir = Join-Path $Scratch 'bom-config'
[void][IO.Directory]::CreateDirectory($configDir)
$cfgFile = Join-Path $configDir 'config.json'
$bakFile = Join-Path $configDir 'config.json.bak'
$valid = [Text.Encoding]::UTF8.GetBytes('{"port":23456,"independentFixture":true}')
$bom = [byte[]]@(0xEF,0xBB,0xBF) + $valid
[IO.File]::WriteAllBytes($cfgFile,$bom)
[IO.File]::WriteAllText($bakFile,'{"oldGood":true}',(New-Object Text.UTF8Encoding($false)))
$configLog = {param($message) $global:IndependentLogs.Add($message)}
$answer = Repair-WbGuardConfig $configDir $configLog
$cfgBytes=[IO.File]::ReadAllBytes($cfgFile); $bakBytes=[IO.File]::ReadAllBytes($bakFile)
$bomBackups=@(Get-ChildItem -LiteralPath $configDir -Filter 'config.json.bak-bom-*')
Check 'config-BOM-stripped-with-exact-original-snapshot-and-good-backup' ($answer -eq 'ok' -and [Convert]::ToBase64String($cfgBytes) -eq [Convert]::ToBase64String($valid) -and [Convert]::ToBase64String($bakBytes) -eq [Convert]::ToBase64String($valid) -and $bomBackups.Count -eq 1 -and [Convert]::ToBase64String([IO.File]::ReadAllBytes($bomBackups[0].FullName)) -eq [Convert]::ToBase64String($bom)) @{status=$answer;originalBytes=$bom.Length;cleanBytes=$cfgBytes.Length;snapshots=$bomBackups.Count}
$lastGood=[Convert]::ToBase64String([IO.File]::ReadAllBytes($bakFile))
$broken=[byte[]]@(0xEF,0xBB,0xBF)+[Text.Encoding]::UTF8.GetBytes('{"broken":')
[IO.File]::WriteAllBytes($cfgFile,$broken)
$answer=Repair-WbGuardConfig $configDir $configLog
Check 'config-broken-JSON-preserves-original-and-last-good-backup' ($answer -eq 'broken' -and [Convert]::ToBase64String([IO.File]::ReadAllBytes($cfgFile)) -eq [Convert]::ToBase64String($broken) -and [Convert]::ToBase64String([IO.File]::ReadAllBytes($bakFile)) -eq $lastGood) @{status=$answer;backupUnchanged=([Convert]::ToBase64String([IO.File]::ReadAllBytes($bakFile)) -eq $lastGood)}
foreach ($invalid in @('[]','null','17')) {
    [IO.File]::WriteAllText($cfgFile,$invalid,(New-Object Text.UTF8Encoding($false)))
    $answer=Repair-WbGuardConfig $configDir $configLog
    Check ('config-reject-non-object-' + $invalid) ($answer -eq 'broken' -and [Convert]::ToBase64String([IO.File]::ReadAllBytes($bakFile)) -eq $lastGood) $answer
}
$report=[pscustomobject]@{version=$PSVersionTable.PSVersion.ToString();cases=$global:IndependentCases.ToArray();logs=$global:IndependentLogs.ToArray()}
[IO.File]::WriteAllText($Evidence,($report|ConvertTo-Json -Depth 20),(New-Object Text.UTF8Encoding($false)))
$failed=@($global:IndependentCases|Where-Object{-not $_.passed})
Write-Output ('INDEPENDENT_MOCK_CASES total={0} passed={1} failed={2}' -f $global:IndependentCases.Count,($global:IndependentCases.Count-$failed.Count),$failed.Count)
if($failed.Count -gt 0){exit 1}
exit 0
