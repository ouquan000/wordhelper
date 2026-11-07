# Create logon tasks that host WB gateway processes outside the DSH process tree.
# Does NOT stop or restart any running service. Safe to run repeatedly.
$ErrorActionPreference = 'Stop'
$panel = 'D:\deepseek_v4.1flash1017\workbuddy2api-panel'
$log = 'D:\deepseek harness\work_anki_enligish\docs\wb-hosting-20261107\setup.log'

function Log($m) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $m" | Tee-Object -FilePath $log -Append }

$defs = @(
    @{ Name = 'WB-MainPool-Boot'; Vbs = "$panel\run\start-silent.vbs" },
    @{ Name = 'WB-Router-Boot';   Vbs = "$panel\run\start-router.vbs" },
    @{ Name = 'WB-Aux-Boot';      Vbs = "$panel\run-bot\start-bot.vbs" }
)

$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable -MultipleInstances IgnoreNew

foreach ($d in $defs) {
    if (-not (Test-Path $d.Vbs)) { Log "SKIP $($d.Name): missing $($d.Vbs)"; continue }
    $action = New-ScheduledTaskAction -Execute 'wscript.exe' -Argument "//B `"$($d.Vbs)`"" -WorkingDirectory $panel
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName $d.Name -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
    Log "OK task $($d.Name) -> $($d.Vbs)"
}

$cutover = 'D:\deepseek harness\work_anki_enligish\docs\wb-hosting-20261107\cutover.ps1'
if (Test-Path $cutover) {
    $when = (Get-Date).AddMinutes(15)
    $a = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$cutover`""
    $t = New-ScheduledTaskTrigger -Once -At $when
    $p = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName 'WB-Hosting-Cutover' -Action $a -Trigger $t -Principal $p -Settings $settings -Force | Out-Null
    Log "OK one-time cutover scheduled at $($when.ToString('yyyy-MM-dd HH:mm:ss'))"
}
