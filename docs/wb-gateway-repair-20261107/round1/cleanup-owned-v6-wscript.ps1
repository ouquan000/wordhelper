$ErrorActionPreference = 'Stop'
$ownedRoot = 'D:\deepseek harness\work_anki_enligish\docs\wb-gateway-repair-20261107\round1\.runtime-g34-37452'
$ownedScript = Join-Path $ownedRoot 'guard-main-missing-recover\run\start-silent.vbs'
$ownedProcess = Get-CimInstance Win32_Process -Filter 'ProcessId = 33156'
$receipt = [ordered]@{root=$ownedRoot;before=$null;action='already-exited';afterPresent=$false}
if ($ownedProcess) {
    if ($ownedProcess.ExecutablePath -ine "$env:SystemRoot\System32\wscript.exe" -or $ownedProcess.CommandLine -notlike ('*"'+$ownedScript+'"*') -or $ownedProcess.ParentProcessId -ne 44788) { throw 'owned-wscript-identity-does-not-match' }
    $receipt.before = $ownedProcess | Select-Object ProcessId,ParentProcessId,ExecutablePath,CommandLine,CreationDate
    Stop-Process -Id $ownedProcess.ProcessId -ErrorAction Stop
    $receipt.action = 'stopped-only-own-confirmed-wscript'
    $receipt.afterPresent = [bool](Get-CimInstance Win32_Process -Filter 'ProcessId = 33156')
}
$receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'g34-v6-owned-wscript-cleanup.json') -Encoding UTF8
if ($receipt.afterPresent) { throw 'own-wscript-still-alive' }
