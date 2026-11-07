param([string]$R2Root,[string]$R2Candidate)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)
$R2HelperBlock=[scriptblock]::Create([IO.File]::ReadAllText((Join-Path $R2Candidate 'wb-service-check.ps1'),[Text.Encoding]::UTF8))
. $R2HelperBlock -Library
$R2GuardBlock=[scriptblock]::Create([IO.File]::ReadAllText((Join-Path $R2Candidate 'wb-gateway-guard.ps1'),[Text.Encoding]::UTF8))
. $R2GuardBlock -Library
function Write-R2Case([string]$R2Name,[bool]$R2Pass,$R2Raw) { [pscustomobject]@{case=$R2Name;pass=$R2Pass;raw=$R2Raw} | ConvertTo-Json -Depth 14 -Compress }
$R2Definitions=@(Get-WbServiceDefinitions (Join-Path $R2Root 'panel'))
Write-R2Case 'G4_definition_coverage' (($R2Definitions.Name -join ',') -eq 'main,aux,router' -and ($R2Definitions.Port -join ',') -eq '8790,8789,8788') $R2Definitions
foreach($R2Def in $R2Definitions) {
 $R2Owner=[pscustomobject]@{ProcessId=77777;ExecutablePath=$R2Def.Executable;CommandLine='';CreationDate='round2'}
 if($R2Def.Name -eq 'router') { $R2Owner.ExecutablePath='C:\Round2\node.exe'; $R2Owner.CommandLine='"C:\Round2\node.exe" "'+$R2Def.Script+'"' }
 $R2Snap=[pscustomobject]@{Success=$true;Owners=@($R2Owner)}
 $R2HealthyJson=[pscustomobject]@{service='workbuddy2api';healthy=2}
 if($R2Def.Name -eq 'router') { $R2HealthyJson=[pscustomobject]@{state='online';qq=[pscustomobject]@{raw=$true};aux=[pscustomobject]@{target='127.0.0.1:8789';healthy=$true;paused=$false};main=[pscustomobject]@{target='127.0.0.1:8790';healthy=$true;reason='ok'};hold=[pscustomobject]@{onlineMs=180000;offlineMs=300000};weight=[pscustomobject]@{main=5;aux=3};counters=[pscustomobject]@{requests=0;toMain=0;toAux=0}} }
 $R2HTTP=[pscustomobject]@{Ok=$true;StatusCode=200;Json=$R2HealthyJson;Reason='round2'}
 $R2Resolved=Resolve-WbServiceState $R2Def $R2Snap $R2HTTP
 Write-R2Case ('G3_library_identity_healthy_'+$R2Def.Name) ($R2Resolved.State -eq 'healthy') $R2Resolved
 $R2Owner.ExecutablePath='C:\Round2\foreign.exe'
 $R2Resolved=Resolve-WbServiceState $R2Def $R2Snap $R2HTTP
 Write-R2Case ('G3_library_foreign_'+$R2Def.Name) ($R2Resolved.State -eq 'foreign' -and $R2Resolved.ExitCode -eq 20) $R2Resolved
 foreach($R2StateName in @('healthy','unhealthy','foreign','missing')) {
  $R2Code=@{healthy=0;unhealthy=30;foreign=20;missing=10}[$R2StateName]
  $R2State=[pscustomobject]@{Name=$R2Def.Name;Port=$R2Def.Port;State=$R2StateName;Reason='independent-'+$R2StateName;OwnerPids=@(77777);ExitCode=$R2Code}
  $script:R2StartCount=0;$script:R2CheckCount=0;$script:R2WaitCount=0;$script:R2Logs=@()
  $R2Check={param($r2d) $script:R2CheckCount++;return $R2State}
  $R2Held={param($r2d) return $false}
  $R2Start={param($r2d) $script:R2StartCount++}
  $R2Wait={param($r2d) $script:R2WaitCount++; [pscustomobject]@{Name=$r2d.Name;Port=$r2d.Port;State='healthy';Reason='round2-simulated-after';OwnerPids=@(77777);ExitCode=0}}
  $R2Log={param($r2m) $script:R2Logs+=@($r2m)}
  $R2Res=Invoke-WbGuardService $R2Def 'ok' $false $R2Check $R2Held $R2Start $R2Wait $R2Log
  $R2ExpectedStarts=0;if($R2StateName -eq 'missing'){$R2ExpectedStarts=1}
  Write-R2Case ('G4_library_'+$R2Def.Name+'_'+$R2StateName) ($script:R2StartCount -eq $R2ExpectedStarts -and (($R2StateName -eq 'missing' -and $R2Res.Action -eq 'recovered') -or ($R2StateName -ne 'missing' -and $R2Res.Action -eq 'none'))) @{result=$R2Res;startCalls=$script:R2StartCount;checkCalls=$script:R2CheckCount;waitCalls=$script:R2WaitCount;log=$script:R2Logs;boundary='library callbacks, not native recovery'}
 }
}
$R2Def=$R2Definitions[0]
$R2State=[pscustomobject]@{Name='main';Port=8790;State='missing';Reason='round2-missing';OwnerPids=@();ExitCode=10}
foreach($R2Scenario in @('held','no-restart','broken-config','race-foreign')) {
 $script:R2StartCount=0;$script:R2CheckCount=0;$script:R2Logs=@()
 $R2Check={param($r2d) $script:R2CheckCount++;if($R2Scenario -eq 'race-foreign' -and $script:R2CheckCount -eq 2){return [pscustomobject]@{Name='main';Port=8790;State='foreign';Reason='round2-new-owner';OwnerPids=@(77778);ExitCode=20}};return $R2State}
 $R2Held={param($r2d) return ($R2Scenario -eq 'held')}
 $R2Start={param($r2d) $script:R2StartCount++}
 $R2Wait={param($r2d) throw 'unexpected wait'}
 $R2Log={param($r2m) $script:R2Logs+=@($r2m)}
 $R2Cfg='ok';if($R2Scenario -eq 'broken-config'){$R2Cfg='broken'}
 $R2Res=Invoke-WbGuardService $R2Def $R2Cfg ($R2Scenario -eq 'no-restart') $R2Check $R2Held $R2Start $R2Wait $R2Log
 Write-R2Case ('G4_library_'+$R2Scenario) ($script:R2StartCount -eq 0 -and $R2Res.Action -eq @{held='held';'no-restart'='no-restart';'broken-config'='config-blocked';'race-foreign'='changed-before-start'}[$R2Scenario]) @{result=$R2Res;startCalls=$script:R2StartCount;log=$script:R2Logs;boundary='library callbacks'}
}
$R2CfgDir=Join-Path $R2Root 'config-probe'
[void][IO.Directory]::CreateDirectory($R2CfgDir)
$R2CfgPath=Join-Path $R2CfgDir 'config.json';$R2BakPath=Join-Path $R2CfgDir 'config.json.bak'
$R2Clean=[Text.Encoding]::UTF8.GetBytes('{"round2":"synthetic","healthy":2}')
$R2Bytes=[byte[]](0xEF,0xBB,0xBF)+$R2Clean
[IO.File]::WriteAllBytes($R2CfgPath,$R2Bytes)
$script:R2Logs=@();$R2Log={param($r2m) $script:R2Logs+=@($r2m)}
$R2Ret=Repair-WbGuardConfig $R2CfgDir $R2Log
$R2After=[IO.File]::ReadAllBytes($R2CfgPath)
Write-R2Case 'G4_config_BOM_sanitize' ($R2Ret -eq 'ok' -and [Convert]::ToBase64String($R2After) -eq [Convert]::ToBase64String($R2Clean) -and (Test-Path -LiteralPath $R2BakPath)) @{result=$R2Ret;originalBase64=[Convert]::ToBase64String($R2Bytes);afterBase64=[Convert]::ToBase64String($R2After);backupExists=(Test-Path -LiteralPath $R2BakPath);logs=$script:R2Logs}
if(-not (Test-Path -LiteralPath $R2BakPath)){[IO.File]::WriteAllBytes($R2BakPath,$R2Clean)}
$R2Bad=[Text.Encoding]::UTF8.GetBytes('{"round2": broken')
[IO.File]::WriteAllBytes($R2CfgPath,$R2Bad);$R2BakBefore=[IO.File]::ReadAllBytes($R2BakPath)
$R2Ret=Repair-WbGuardConfig $R2CfgDir $R2Log
Write-R2Case 'G4_broken_config_preserve' ($R2Ret -eq 'broken' -and [Convert]::ToBase64String([IO.File]::ReadAllBytes($R2CfgPath)) -eq [Convert]::ToBase64String($R2Bad) -and [Convert]::ToBase64String([IO.File]::ReadAllBytes($R2BakPath)) -eq [Convert]::ToBase64String($R2BakBefore)) @{result=$R2Ret;originalBase64=[Convert]::ToBase64String($R2Bad);afterBase64=[Convert]::ToBase64String([IO.File]::ReadAllBytes($R2CfgPath));backupBase64=[Convert]::ToBase64String([IO.File]::ReadAllBytes($R2BakPath));logs=$script:R2Logs}
