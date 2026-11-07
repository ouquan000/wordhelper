param([string]$R2Runtime)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false)
$R2All=@(Get-CimInstance Win32_Process -Filter "Name='powershell.exe' OR Name='cmd.exe' OR Name='wscript.exe' OR Name='wb2api.exe' OR Name='node.exe'")
$R2Matches=@($R2All | Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($R2Runtime,[StringComparison]::OrdinalIgnoreCase) -ge 0 -and $_.ProcessId -ne $PID } | Select-Object ProcessId,ParentProcessId,ExecutablePath,CommandLine,@{n='CreationDate';e={[string]$_.CreationDate}})
ConvertTo-Json -Depth 7 -InputObject @($R2Matches)
