param([string]$R2Root,[string]$R2PidCsv,[string]$R2PortCsv)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)
$R2Ids=@($R2PidCsv.Split(',') | Where-Object {$_} | ForEach-Object {[int]$_})
$R2Ports=@($R2PortCsv.Split(',') | Where-Object {$_} | ForEach-Object {[int]$_})
$R2Processes=@()
foreach($R2Id in $R2Ids) {
 $R2Found=@(Get-CimInstance -ClassName Win32_Process -Filter ('ProcessId = '+$R2Id) -ErrorAction Stop)
 foreach($R2P in $R2Found) {
  $R2Own=([string]$R2P.ExecutablePath).StartsWith($R2Root,[StringComparison]::OrdinalIgnoreCase) -or ([string]$R2P.CommandLine).Contains($R2Root)
  $R2Processes+= [pscustomobject]@{ProcessId=$R2P.ProcessId;CreationDate=[string]$R2P.CreationDate;ExecutablePath=if($R2Own){$R2P.ExecutablePath}else{'<unrelated PID reuse; path excluded>'};CommandLine=if($R2Own){$R2P.CommandLine}else{'<excluded>'};MatchesRound2=$R2Own}
 }
}
$R2Filter="Name = 'node.exe' OR Name = 'python.exe' OR Name = 'wb2api.exe' OR Name = 'powershell.exe' OR Name = 'cmd.exe' OR Name = 'wscript.exe'"
$R2RuntimeOwners=@(Get-CimInstance -ClassName Win32_Process -Filter $R2Filter -ErrorAction Stop | Where-Object { (([string]$_.ExecutablePath).Replace('/','\').StartsWith($R2Root,[StringComparison]::OrdinalIgnoreCase) -or ([string]$_.CommandLine).Replace('/','\').Contains($R2Root)) -and ([string]$_.CommandLine) -notlike '*runner.js*' } | Select-Object ProcessId,ParentProcessId,ExecutablePath,CommandLine,@{n='CreationDate';e={[string]$_.CreationDate}},@{n='AllowedAuditor';e={([string]$_.CommandLine) -match '(finalize_round2\.py|final_inventory\.ps1)'}})
$R2Listeners=@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {$_.LocalPort -in $R2Ports} | Select-Object LocalAddress,LocalPort,OwningProcess)
[pscustomobject]@{Timestamp=[DateTimeOffset]::Now.ToString('o');RequestedOwnedPids=$R2Ids;RequestedOwnedPorts=$R2Ports;StillPresentRequestedPids=$R2Processes;RuntimeOwners=$R2RuntimeOwners;OwnPortListeners=$R2Listeners;Mutation='none: read-only CIM and TCP inventory'} | ConvertTo-Json -Depth 8
