[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$IndependentTargetHelper,
    [Parameter(Mandatory=$true)][string]$IndependentPortMap,
    [ValidateSet('main','aux','router')][string]$Service = 'main',
    [string]$PanelRoot,
    [int]$WaitSeconds = 0
)
$ErrorActionPreference = 'Stop'
# Save CLI arguments under unique names because the candidate has its own param block.
$selectedService = $Service
$selectedRoot = $PanelRoot
$requestedWait = $WaitSeconds
$selectedHelper = $IndependentTargetHelper
$selectedMap = $IndependentPortMap
. $selectedHelper -Library
$definitions = @(Get-WbServiceDefinitions $selectedRoot)
$mapping = [IO.File]::ReadAllText($selectedMap) | ConvertFrom-Json
foreach ($definition in $definitions) {
    $definition.Port = [int]$mapping.($definition.Name)
    $definition.MainPort = [int]$mapping.main
    $definition.AuxPort = [int]$mapping.aux
    if ($definition.Port -le 1024 -or $definition.Port -in @(8788,8789,8790,6199)) { throw 'independent-port-is-not-isolated' }
}
$definition = @($definitions | Where-Object {$_.Name -eq $selectedService})[0]
$result = Wait-WbServiceState $definition $requestedWait
Write-Output ('WB_CHECK name={0} port={1} state={2} reason={3} pids={4}' -f $result.Name, $result.Port, $result.State, $result.Reason, ($result.OwnerPids -join ','))
exit ([int]$result.ExitCode)
