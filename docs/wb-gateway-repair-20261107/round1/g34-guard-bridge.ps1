[CmdletBinding()]
param([switch]$Library)
# Only transport addresses are adapted. Candidate helpers/guard remain byte-identical.
$global:IndependentMapPath = Join-Path $PSScriptRoot 'port-map.json'
$global:IndependentWaitTrace = Join-Path $PSScriptRoot 'wait-calls.jsonl'
$global:IndependentHelperPath = Join-Path $PSScriptRoot 'run\wb-service-check.ps1'
. $global:IndependentHelperPath -Library
$global:IndependentOriginalDefinitions = ${function:Get-WbServiceDefinitions}
$global:IndependentOriginalWait = ${function:Wait-WbServiceState}
function Get-WbServiceDefinitions([string]$Root) {
    $mapping = [IO.File]::ReadAllText($global:IndependentMapPath) | ConvertFrom-Json
    $definitions = @(& $global:IndependentOriginalDefinitions $Root)
    foreach ($definition in $definitions) {
        $definition.Port = [int]$mapping.($definition.Name)
        $definition.MainPort = [int]$mapping.main
        $definition.AuxPort = [int]$mapping.aux
        if ($definition.Port -le 1024 -or $definition.Port -in @(8788,8789,8790,6199)) { throw 'independent-port-is-not-isolated' }
    }
    return $definitions
}
function Wait-WbServiceState($Definition, [int]$Seconds = 20, [int]$TimeoutMs = 2500, [int]$MaxBytes = 32768) {
    $line = [pscustomobject]@{Name=$Definition.Name; Seconds=$Seconds; Port=$Definition.Port} | ConvertTo-Json -Compress
    Add-Content -LiteralPath $global:IndependentWaitTrace -Value $line -Encoding UTF8
    return (& $global:IndependentOriginalWait $Definition $Seconds $TimeoutMs $MaxBytes)
}
