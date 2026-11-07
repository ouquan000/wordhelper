param([string]$R2Out = 'D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2')
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$R2Ports = @(8788,8789,8790)
$R2Sockets = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -in $R2Ports })
$R2Owners = foreach ($R2Id in @($R2Sockets | Select-Object -ExpandProperty OwningProcess -Unique)) {
 $R2Proc = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $R2Id)
 [pscustomobject]@{ProcessId=$R2Proc.ProcessId; ExecutablePath=$R2Proc.ExecutablePath; CommandLine=$R2Proc.CommandLine; CreationDate=[string]$R2Proc.CreationDate}
}
[pscustomobject]@{Timestamp=(Get-Date).ToString('o'); PSVersion=$PSVersionTable.PSVersion.ToString(); Listeners=@($R2Sockets | Select-Object LocalAddress,LocalPort,OwningProcess); Owners=@($R2Owners)} | ConvertTo-Json -Depth 8
