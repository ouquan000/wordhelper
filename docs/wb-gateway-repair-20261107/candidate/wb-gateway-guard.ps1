<# Three-service WB guard, Windows PowerShell 5.1.
   Dot-source / -Library: definitions only; no config repair, log, watcher or service start.
   Only a positively established missing listener may be recovered. Existing foreign
   or unhealthy listeners are reported and left running. NoRestart also covers watcher.
   Install helper in the formal panel run directory before installing this guard. #>
[CmdletBinding()]
param(
    [switch]$NoRestart,
    [switch]$Quiet,
    [switch]$Library,
    [string]$PanelRoot = 'D:\deepseek_v4.1flash1017\workbuddy2api-panel',
    [string]$LogPath = 'D:\Apps\Bot\health\wb-guard.log',
    [string]$HelperPath = 'D:\deepseek_v4.1flash1017\workbuddy2api-panel\run\wb-service-check.ps1',
    [string]$WatchScript = 'D:\deepseek_v4.1flash1017\thebigfish\tools\wb-bom-watch.ps1',
    [string]$WatchPidFile = 'D:\Apps\Bot\health\wb-bom-watch.pid',
    [ValidateRange(0, 60)][int]$StartWaitSeconds = 20
)

function Write-WbGuardLog([string]$Path, [string]$Message, [bool]$Silent = $false) {
    $parent = Split-Path $Path -Parent
    if ($parent) { [void][IO.Directory]::CreateDirectory($parent) }
    $line = '{0} [guard] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Add-Content -LiteralPath $Path -Value $line -Encoding UTF8 -ErrorAction Stop
    if (-not $Silent) { Write-Host $line }
}

function Repair-WbGuardConfig([string]$Dir, [scriptblock]$Log) {
    $cfg = Join-Path $Dir 'config.json'
    try {
        if (-not (Test-Path -LiteralPath $cfg -PathType Leaf)) { $null = & $Log ('MISSING config ' + $cfg); return 'missing' }
        $original = [IO.File]::ReadAllBytes($cfg)
        $bytes = $original
        $bom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
        if ($bom) {
            $bytes = New-Object byte[] ($original.Length - 3)
            [Array]::Copy($original, 3, $bytes, 0, $bytes.Length)
        }
        # Validate BEFORE writing. Invalid JSON never replaces the last good backup.
        $utf8 = New-Object Text.UTF8Encoding($false, $true)
        try {
            $text = $utf8.GetString($bytes)
            if ($text -notmatch '^\s*\{') { throw 'config-not-object' }
            $parsed = ConvertFrom-Json -InputObject $text -ErrorAction Stop
            if ($null -eq $parsed -or $parsed -isnot [pscustomobject]) { throw 'config-not-object' }
        } catch { $null = & $Log ('BROKEN JSON config ' + $cfg + '; no repair performed'); return 'broken' }
        if ($bom) {
            $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fffffff'
            Copy-Item -LiteralPath $cfg -Destination ($cfg + '.bak-bom-' + $stamp) -ErrorAction Stop
            [IO.File]::WriteAllBytes($cfg, $bytes)
            $null = & $Log ('HEALED BOM stripped: ' + $cfg)
        }
        $bak = Join-Path $Dir 'config.json.bak'
        $same = $false
        if (Test-Path -LiteralPath $bak -PathType Leaf) {
            $backup = [IO.File]::ReadAllBytes($bak)
            if ($backup.Length -eq $bytes.Length) {
                $same = $true
                for ($i = 0; $i -lt $bytes.Length; $i++) { if ($backup[$i] -ne $bytes[$i]) { $same = $false; break } }
            }
        }
        if (-not $same) {
            [IO.File]::WriteAllBytes($bak, $bytes)
            $null = & $Log ('HEALED config.json.bak refreshed: ' + $bak)
        }
        return 'ok'
    } catch { $null = & $Log ('ERROR config repair failed: ' + $cfg); return 'error' }
}

function New-WbGuardResult($State, [string]$Config, [string]$Action, [int]$Code = -1) {
    if ($Code -lt 0) { $Code = [int]$State.ExitCode }
    return [pscustomobject]@{ Name = $State.Name; Port = $State.Port; State = $State.State; Reason = $State.Reason; Config = $Config; Action = $Action; Up = ($State.State -eq 'healthy'); ExitCode = $Code }
}

function Write-WbGuardState($State, [scriptblock]$Log, [string]$Prefix = '') {
    $level = switch ($State.State) { 'healthy' { 'HEALTHY' } 'missing' { 'MISSING' } 'foreign' { 'CONFLICT' } 'unhealthy' { 'WARN UNHEALTHY' } default { 'ERROR INSPECTION' } }
    $null = & $Log ('{0} {1} {2}:{3} reason={4} pids={5}' -f $level, $Prefix, $State.Name, $State.Port, $State.Reason, ($State.OwnerPids -join ','))
}

function Invoke-WbGuardService($Definition, [string]$Config, [bool]$RestartForbidden, [scriptblock]$Check, [scriptblock]$IsHeld, [scriptblock]$Start, [scriptblock]$Wait, [scriptblock]$Log) {
    $state = & $Check $Definition
    Write-WbGuardState $state $Log
    if ($state.State -ne 'missing') {
        $code = [int]$state.ExitCode
        if ($Config -notin @('ok', 'not-applicable')) { $code = 40 }
        return (New-WbGuardResult $state $Config 'none' $code)
    }
    if ($Config -notin @('ok', 'not-applicable')) { return (New-WbGuardResult $state $Config 'config-blocked' 40) }
    if ($RestartForbidden) {
        $null = & $Log ('NO_RESTART ' + $Definition.Name + '; missing listener left unchanged')
        return (New-WbGuardResult $state $Config 'no-restart')
    }
    if (& $IsHeld $Definition) {
        $null = & $Log ('HOLD ' + $Definition.Name + '; .hold-restart prevents recovery')
        return (New-WbGuardResult $state $Config 'held')
    }
    # A second identity check closes the ordinary missing->new-owner race before
    # calling the original starter. No stop/kill is ever part of the recovery.
    $fresh = & $Check $Definition
    if ($fresh.State -ne 'missing') {
        Write-WbGuardState $fresh $Log 'before-start'
        return (New-WbGuardResult $fresh $Config 'changed-before-start')
    }
    if (& $IsHeld $Definition) { return (New-WbGuardResult $fresh $Config 'held') }
    try {
        $null = & $Log ('RECOVER missing ' + $Definition.Name + ' via ' + $Definition.Starter)
        $null = & $Start $Definition
    } catch {
        $null = & $Log ('ERROR starter failed for ' + $Definition.Name)
        return (New-WbGuardResult $fresh $Config 'start-failed' 40)
    }
    $after = & $Wait $Definition
    Write-WbGuardState $after $Log 'after-recovery'
    $action = 'recovery-failed'
    if ($after.State -eq 'healthy') { $action = 'recovered' }
    return (New-WbGuardResult $after $Config $action)
}

function Test-WbWatchOwner($Owner, [string]$ScriptPath) {
    if ($null -eq $Owner -or [int]$Owner.ProcessId -le 0) { return $false }
    if ([IO.Path]::GetFileName([string]$Owner.ExecutablePath) -inotmatch '^(powershell|pwsh)\.exe$') { return $false }
    try { $argv = @(Split-WbCommandLine ([string]$Owner.CommandLine)) } catch { return $false }
    if ($argv.Count -lt 3) { return $false }
    $exeLeaf = [IO.Path]::GetFileName([string]$Owner.ExecutablePath)
    if (-not (Test-WbExactPath $argv[0] $Owner.ExecutablePath) -and -not [string]::Equals($argv[0], $exeLeaf, [StringComparison]::OrdinalIgnoreCase)) { return $false }
    for ($i = 1; $i -lt $argv.Count - 1; $i++) {
        if ($argv[$i] -in @('-Command', '-EncodedCommand', '-c', '-e', '-ec')) { return $false }
        if ($argv[$i] -ieq '-File') { return (Test-WbExactPath $argv[$i + 1] $ScriptPath) }
    }
    return $false
}

function Get-WbWatchStatus([string]$ScriptPath, [string]$PidFile) {
    try {
        # A reused pid must not count as the watcher. Enumerating exact script
        # identity also avoids duplication if a running watcher lost its pid file.
        $owners = @(Get-CimInstance -ClassName Win32_Process -Filter "Name = 'powershell.exe' OR Name = 'pwsh.exe'" -ErrorAction Stop)
        $knownId = 0
        if (Test-Path -LiteralPath $PidFile -PathType Leaf) {
            [void][int]::TryParse(([IO.File]::ReadAllText($PidFile)).Trim(), [ref]$knownId)
        }
        foreach ($owner in $owners) {
            if (Test-WbWatchOwner $owner $ScriptPath) { return [pscustomobject]@{ State = 'healthy'; ProcessId = $owner.ProcessId; PidFileMatches = ($knownId -eq $owner.ProcessId) } }
        }
        return [pscustomobject]@{ State = 'missing'; ProcessId = 0; PidFileMatches = $false }
    } catch { return [pscustomobject]@{ State = 'error'; ProcessId = 0; PidFileMatches = $false } }
}

function Invoke-WbGuardWatch([string]$ScriptPath, [bool]$RestartForbidden, [scriptblock]$Inspect, [scriptblock]$Start, [scriptblock]$Log) {
    $state = & $Inspect
    if ($state.State -eq 'healthy') { $null = & $Log ('HEALTHY wb-bom-watch pid=' + $state.ProcessId); return 0 }
    if ($state.State -ne 'missing') { $null = & $Log 'ERROR wb-bom-watch inspection failed; no launch'; return 40 }
    if ($RestartForbidden) { $null = & $Log 'NO_RESTART wb-bom-watch missing; no launch'; return 10 }
    if (-not (Test-Path -LiteralPath $ScriptPath -PathType Leaf)) { $null = & $Log 'MISSING wb-bom-watch script'; return 40 }
    try { $null = & $Start; $null = & $Log 'WATCHDOG wb-bom-watch missing; original watcher launched'; return 0 }
    catch { $null = & $Log 'ERROR wb-bom-watch launch failed'; return 40 }
}

function Invoke-WbGatewayGuard([string]$Root, [string]$SharedHelper, [string]$LogFile, [string]$BomWatchScript, [string]$BomWatchPid, [bool]$RestartForbidden = $false, [bool]$Silent = $false, [int]$RecoveryWaitSeconds = 20) {
    $ErrorActionPreference = 'Stop'
    $log = { param($message) Write-WbGuardLog $LogFile $message $Silent }
    if (-not (Test-Path -LiteralPath $SharedHelper -PathType Leaf)) {
        $null = & $log 'ERROR shared run/wb-service-check.ps1 missing; no repairs or starts'
        return [pscustomobject]@{ ExitCode = 40; Services = @(); WatchCode = 40 }
    }
    # Import only defines functions; helper never starts anything when dot-sourced.
    . $SharedHelper -Library
    $configByDir = @{}
    $definitions = @(Get-WbServiceDefinitions $Root)
    foreach ($definition in $definitions) {
        if ($definition.NeedsConfig -and -not $configByDir.ContainsKey($definition.Dir)) {
            $configByDir[$definition.Dir] = Repair-WbGuardConfig $definition.Dir $log
        }
    }
    $check = { param($definition) Get-WbServiceState $definition }
    $held = { param($definition) Test-Path -LiteralPath (Join-Path $definition.Dir '.hold-restart') }
    $start = {
        param($definition)
        $starter = Join-Path $definition.Dir $definition.Starter
        if (Test-Path -LiteralPath (Join-Path $definition.Dir '.hold-restart')) { throw 'hold-restart-appeared' }
        if (-not (Test-Path -LiteralPath $starter -PathType Leaf)) { throw 'starter-missing' }
        Start-Process -FilePath 'wscript.exe' -ArgumentList @('//B', ('"' + $starter + '"')) -WorkingDirectory $definition.Dir -ErrorAction Stop | Out-Null
    }
    $wait = { param($definition) Wait-WbServiceState $definition $RecoveryWaitSeconds }
    $results = @()
    foreach ($definition in $definitions) {
        $cfgState = 'not-applicable'
        if ($definition.NeedsConfig) { $cfgState = $configByDir[$definition.Dir] }
        $results += Invoke-WbGuardService $definition $cfgState $RestartForbidden $check $held $start $wait $log
    }
    $watchInspect = { Get-WbWatchStatus $BomWatchScript $BomWatchPid }
    $watchStart = {
        Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', ('"' + $BomWatchScript + '"')) -WindowStyle Hidden -ErrorAction Stop | Out-Null
    }
    $watchCode = Invoke-WbGuardWatch $BomWatchScript $RestartForbidden $watchInspect $watchStart $log
    $exitCode = [int]$watchCode
    foreach ($result in $results) { $exitCode = [Math]::Max($exitCode, [int]$result.ExitCode) }
    return [pscustomobject]@{ ExitCode = $exitCode; Services = @($results); WatchCode = $watchCode }
}

if ($MyInvocation.InvocationName -ne '.' -and -not $Library) {
    try {
        $report = Invoke-WbGatewayGuard $PanelRoot $HelperPath $LogPath $WatchScript $WatchPidFile ([bool]$NoRestart) ([bool]$Quiet) $StartWaitSeconds
        if (-not $Quiet) { $report.Services | Format-Table Name, Port, State, Config, Action, ExitCode -AutoSize | Out-String | Write-Output }
        exit ([int]$report.ExitCode)
    } catch {
        # Do not expose configuration or arbitrary HTTP/process exception content.
        Write-Error 'WB_GUARD fatal inspection/repair failure; no success is claimed.' -ErrorAction Continue
        exit 40
    }
}
