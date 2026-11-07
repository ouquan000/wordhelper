<# Read-only WB listener/HTTP identity checks. Compatible with Windows PowerShell 5.1.
   Install beside run\start-silent.cmd. Dot-sourcing defines functions only.
   CLI codes: healthy=0, missing=10, foreign=20, unhealthy=30, inspection-error=40.
   No function in this file starts, stops, or repairs a service/configuration. #>
[CmdletBinding()]
param(
    [ValidateSet('main', 'aux', 'router')][string]$Service = 'main',
    [string]$PanelRoot = 'D:\deepseek_v4.1flash1017\workbuddy2api-panel',
    [ValidateRange(0, 60)][int]$WaitSeconds = 0,
    [ValidateRange(100, 10000)][int]$TimeoutMs = 2500,
    [ValidateRange(256, 1048576)][int]$MaxBytes = 32768,
    [switch]$Library
)

function ConvertTo-WbPath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or $Path.IndexOf('"') -ge 0) { return '' }
    try {
        # Full, rooted paths only; no CWD-dependent identity comparisons.
        if ($Path -notmatch '^(?:[A-Za-z]:[\\/]|\\\\)') { return '' }
        return [IO.Path]::GetFullPath($Path.Replace('/', '\')).TrimEnd('\')
    } catch { return '' }
}

function Test-WbExactPath([string]$Actual, [string]$Expected) {
    $a = ConvertTo-WbPath $Actual
    $b = ConvertTo-WbPath $Expected
    return ($a.Length -gt 0 -and $b.Length -gt 0 -and [string]::Equals($a, $b, [StringComparison]::OrdinalIgnoreCase))
}

function Split-WbCommandLine([string]$CommandLine) {
    # Windows CRT quoting rules, including backslashes immediately before a quote.
    $items = New-Object 'System.Collections.Generic.List[string]'
    $index = 0
    while ($index -lt $CommandLine.Length) {
        while ($index -lt $CommandLine.Length -and [char]::IsWhiteSpace($CommandLine[$index])) { $index++ }
        if ($index -ge $CommandLine.Length) { break }
        $token = New-Object Text.StringBuilder
        $quoted = $false
        while ($index -lt $CommandLine.Length) {
            $c = $CommandLine[$index]
            if (-not $quoted -and [char]::IsWhiteSpace($c)) { break }
            $slashes = 0
            while ($index -lt $CommandLine.Length -and $CommandLine[$index] -eq '\') { $slashes++; $index++ }
            if ($index -lt $CommandLine.Length -and $CommandLine[$index] -eq '"') {
                [void]$token.Append(('\' * [int][Math]::Floor($slashes / 2)))
                if (($slashes % 2) -eq 1) { [void]$token.Append('"') }
                elseif ($quoted -and $index + 1 -lt $CommandLine.Length -and $CommandLine[$index + 1] -eq '"') {
                    [void]$token.Append('"'); $index++
                } else { $quoted = -not $quoted }
                $index++
            } else {
                if ($slashes -gt 0) { [void]$token.Append(('\' * $slashes)) }
                if ($index -lt $CommandLine.Length) {
                    if (-not $quoted -and [char]::IsWhiteSpace($CommandLine[$index])) { break }
                    [void]$token.Append($CommandLine[$index]); $index++
                }
            }
        }
        if ($quoted) { throw 'unbalanced-command-line-quote' }
        $items.Add($token.ToString())
    }
    return $items.ToArray()
}

function Get-WbServiceDefinitions([string]$Root = 'D:\deepseek_v4.1flash1017\workbuddy2api-panel') {
    $mainDir = Join-Path $Root 'run'
    $auxDir = Join-Path $Root 'run-bot'
    @(
        [pscustomobject]@{ Name = 'main'; Port = 8790; Dir = $mainDir; Executable = (Join-Path $mainDir 'wb2api.exe'); Script = ''; Endpoint = '/healthz'; Starter = 'start-silent.vbs'; NeedsConfig = $true; MainPort = 8790; AuxPort = 8789 },
        [pscustomobject]@{ Name = 'aux'; Port = 8789; Dir = $auxDir; Executable = (Join-Path $auxDir 'wb2api.exe'); Script = ''; Endpoint = '/healthz'; Starter = 'start-bot.vbs'; NeedsConfig = $true; MainPort = 8790; AuxPort = 8789 },
        [pscustomobject]@{ Name = 'router'; Port = 8788; Dir = $mainDir; Executable = 'node.exe'; Script = (Join-Path $mainDir 'wb-router.js'); Endpoint = '/_router/status'; Starter = 'start-router.vbs'; NeedsConfig = $false; MainPort = 8790; AuxPort = 8789 }
    )
}

function Test-WbOwnerIdentity($Definition, $Owner) {
    if ($null -eq $Owner -or [int]$Owner.ProcessId -le 0) { return $false }
    if ($Definition.Name -ne 'router') { return (Test-WbExactPath $Owner.ExecutablePath $Definition.Executable) }
    if ([IO.Path]::GetFileName([string]$Owner.ExecutablePath) -ine 'node.exe') { return $false }
    try { $argv = @(Split-WbCommandLine ([string]$Owner.CommandLine)) } catch { return $false }
    # The deployed starter runs node.exe SCRIPT. A path hidden in -e/--eval or
    # after a different entry script must never establish ownership.
    return ($argv.Count -ge 2 -and (Test-WbExactPath $argv[0] $Owner.ExecutablePath) -and (Test-WbExactPath $argv[1] $Definition.Script))
}

function Test-WbNumber($Value) {
    return ($Value -is [byte] -or $Value -is [sbyte] -or $Value -is [int16] -or $Value -is [uint16] -or $Value -is [int32] -or $Value -is [uint32] -or $Value -is [int64] -or $Value -is [uint64] -or $Value -is [single] -or $Value -is [double] -or $Value -is [decimal])
}

function Test-WbCount($Value, [switch]$Positive) {
    if (-not (Test-WbNumber $Value)) { return $false }
    $n = [double]$Value
    if ([double]::IsNaN($n) -or [double]::IsInfinity($n) -or [Math]::Floor($n) -ne $n) { return $false }
    if ($Positive) { return ($n -gt 0) }
    return ($n -ge 0)
}

function Test-WbJsonObject($Value) { return ($null -ne $Value -and $Value -is [pscustomobject]) }

function Test-WbHealthJson($Json) {
    if (-not (Test-WbJsonObject $Json)) { return [pscustomobject]@{ Ok = $false; Reason = 'health-json-not-object' } }
    if ($Json.service -isnot [string] -or $Json.service -cne 'workbuddy2api') { return [pscustomobject]@{ Ok = $false; Reason = 'health-service-identity-mismatch' } }
    if (-not (Test-WbCount $Json.healthy -Positive)) { return [pscustomobject]@{ Ok = $false; Reason = 'health-healthy-count-not-positive-integer' } }
    return [pscustomobject]@{ Ok = $true; Reason = 'workbuddy2api-healthy' }
}

function Test-WbRouterJson($Json, [int]$MainPort = 8790, [int]$AuxPort = 8789) {
    $bad = [pscustomobject]@{ Ok = $false; Reason = 'router-status-schema-or-target-mismatch' }
    if (-not (Test-WbJsonObject $Json) -or $Json.state -cnotin @('online', 'offline')) { return $bad }
    if (-not (Test-WbJsonObject $Json.qq) -or -not (Test-WbJsonObject $Json.aux)) { return $bad }
    if ($Json.aux.target -cne ('127.0.0.1:' + $AuxPort) -or $Json.aux.healthy -isnot [bool] -or $Json.aux.paused -isnot [bool]) { return $bad }
    if (-not (Test-WbJsonObject $Json.hold) -or -not (Test-WbCount $Json.hold.onlineMs) -or -not (Test-WbCount $Json.hold.offlineMs)) { return $bad }
    if (-not (Test-WbJsonObject $Json.weight) -or -not (Test-WbCount $Json.weight.main -Positive) -or -not (Test-WbCount $Json.weight.aux -Positive)) { return $bad }
    if (-not (Test-WbJsonObject $Json.counters)) { return $bad }
    foreach ($key in @('requests', 'toMain', 'toAux')) { if (-not (Test-WbCount $Json.counters.$key)) { return $bad } }
    # Legacy status has no main health fields. Its strong stable schema plus the
    # exact node/script owner is sufficient; main and aux get separate live probes.
    if ($null -ne $Json.main) {
        if (-not (Test-WbJsonObject $Json.main) -or $Json.main.target -cne ('127.0.0.1:' + $MainPort)) { return $bad }
        if ($null -ne $Json.main.healthy -and $Json.main.healthy -isnot [bool]) { return $bad }
        if ($null -ne $Json.main.reason -and $Json.main.reason -isnot [string]) { return $bad }
    }
    foreach ($key in @('mainOk', 'auxOk')) { if ($null -ne $Json.$key -and $Json.$key -isnot [bool]) { return $bad } }
    foreach ($key in @('mainReason', 'auxReason')) { if ($null -ne $Json.$key -and $Json.$key -isnot [string]) { return $bad } }
    $reason = 'router-status-healthy'
    if ($null -eq $Json.mainOk -and ($null -eq $Json.main -or $null -eq $Json.main.healthy)) { $reason = 'router-status-legacy-main-not-reported' }
    return [pscustomobject]@{ Ok = $true; Reason = $reason }
}

function Get-WbListenerSnapshot([int]$Port) {
    try {
        # Enumerate LISTEN only, then match the numeric port. Discovery failure is
        # not an empty port and must never authorize a start.
        $connections = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -eq $Port })
        $owners = @()
        foreach ($ownerId in @($connections | Select-Object -ExpandProperty OwningProcess -Unique)) {
            $process = Get-CimInstance -ClassName Win32_Process -Filter ('ProcessId = ' + [int]$ownerId) -ErrorAction Stop
            if ($null -eq $process) { throw 'listener-process-disappeared' }
            $owners += [pscustomobject]@{ ProcessId = [int]$process.ProcessId; ExecutablePath = [string]$process.ExecutablePath; CommandLine = [string]$process.CommandLine; CreationDate = [string]$process.CreationDate }
        }
        return [pscustomobject]@{ Success = $true; Owners = @($owners); Reason = '' }
    } catch { return [pscustomobject]@{ Success = $false; Owners = @(); Reason = 'listener-or-process-inspection-failed' } }
}

function Invoke-WbBoundedJsonGet([string]$Uri, [int]$TimeoutMs = 2500, [int]$MaxBytes = 32768) {
    $response = $null
    $stream = $null
    $memory = $null
    $request = $null
    $status = 0
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        if ($TimeoutMs -lt 100 -or $MaxBytes -lt 1) { throw 'invalid-http-bounds' }
        $request = [Net.HttpWebRequest][Net.WebRequest]::Create($Uri)
        $request.Method = 'GET'
        $request.Proxy = $null
        $request.AllowAutoRedirect = $false
        $request.KeepAlive = $false
        $request.Timeout = $TimeoutMs
        $request.ReadWriteTimeout = $TimeoutMs
        $request.MaximumResponseHeadersLength = 16
        $request.Accept = 'application/json'
        $response = [Net.HttpWebResponse]$request.GetResponse()
        $status = [int]$response.StatusCode
        if ($status -ne 200) { throw ('http-' + $status) }
        if ($response.ContentType -notmatch '(?i)^application/(?:json|[a-z0-9.+-]+\+json)(?:\s*;|\s*$)') { throw 'http-content-type-not-json' }
        if ($response.ContentLength -gt $MaxBytes) { throw 'http-body-too-large' }
        $stream = $response.GetResponseStream()
        $memory = New-Object IO.MemoryStream
        $buffer = New-Object byte[] ([Math]::Min(4096, $MaxBytes + 1))
        while ($true) {
            $remaining = $TimeoutMs - [int]$timer.ElapsedMilliseconds
            if ($remaining -le 0) { throw 'http-total-timeout' }
            $stream.ReadTimeout = [Math]::Max(1, $remaining)
            $size = $stream.Read($buffer, 0, [Math]::Min($buffer.Length, $MaxBytes + 1 - [int]$memory.Length))
            if ($size -eq 0) { break }
            $memory.Write($buffer, 0, $size)
            if ($memory.Length -gt $MaxBytes) { throw 'http-body-too-large' }
        }
        if ($timer.ElapsedMilliseconds -gt $TimeoutMs) { throw 'http-total-timeout' }
        $utf8 = New-Object Text.UTF8Encoding($false, $true)
        $text = $utf8.GetString($memory.ToArray())
        if ($text -notmatch '^\s*\{') { throw 'http-json-not-object' }
        try { $json = ConvertFrom-Json -InputObject $text -ErrorAction Stop } catch { throw 'http-invalid-json' }
        if (-not (Test-WbJsonObject $json)) { throw 'http-json-not-object' }
        return [pscustomobject]@{ Ok = $true; StatusCode = $status; Json = $json; Reason = 'http-json-ok'; Bytes = [int]$memory.Length }
    } catch {
        # Never print a response body, request headers, or arbitrary exception text.
        $reason = 'http-request-failed-or-timeout'
        if ($_.Exception.Message -match '^(http-[a-z0-9-]+|invalid-http-bounds)$') { $reason = $_.Exception.Message }
        $exception = $_.Exception
        while ($null -ne $exception.InnerException -and $exception -isnot [Net.WebException]) { $exception = $exception.InnerException }
        if ($exception -is [Net.WebException] -and $null -ne $exception.Response) {
            $status = [int]$exception.Response.StatusCode
            $exception.Response.Close()
            $reason = 'http-' + $status
        }
        return [pscustomobject]@{ Ok = $false; StatusCode = $status; Json = $null; Reason = $reason; Bytes = 0 }
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
        if ($null -ne $memory) { $memory.Dispose() }
        if ($null -ne $response) { $response.Close() }
        if ($null -ne $request) { $request.Abort() }
        $timer.Stop()
    }
}

function New-WbServiceState($Definition, [string]$State, [string]$Reason, [object[]]$Owners = @()) {
    $codes = @{ healthy = 0; missing = 10; foreign = 20; unhealthy = 30; error = 40 }
    return [pscustomobject]@{ Name = $Definition.Name; Port = [int]$Definition.Port; State = $State; Reason = $Reason; ExitCode = [int]$codes[$State]; OwnerPids = @($Owners | ForEach-Object { [int]$_.ProcessId }) }
}

function Resolve-WbServiceState($Definition, $Snapshot, $HttpResult) {
    if ($null -eq $Snapshot -or -not $Snapshot.Success) { return (New-WbServiceState $Definition 'error' 'listener-or-process-inspection-failed') }
    $owners = @($Snapshot.Owners)
    if ($owners.Count -eq 0) { return (New-WbServiceState $Definition 'missing' 'no-listener') }
    foreach ($owner in $owners) {
        if (-not (Test-WbOwnerIdentity $Definition $owner)) { return (New-WbServiceState $Definition 'foreign' 'CONFLICT-listener-owner-identity-mismatch' $owners) }
    }
    if ($null -eq $HttpResult -or -not $HttpResult.Ok -or $HttpResult.StatusCode -ne 200) {
        $reason = 'http-check-not-completed'
        if ($null -ne $HttpResult) {
            $reason = $HttpResult.Reason
            if ($HttpResult.Ok -and $HttpResult.StatusCode -ne 200) { $reason = 'http-' + $HttpResult.StatusCode }
        }
        return (New-WbServiceState $Definition 'unhealthy' $reason $owners)
    }
    if ($Definition.Name -eq 'router') { $validation = Test-WbRouterJson $HttpResult.Json $Definition.MainPort $Definition.AuxPort }
    else { $validation = Test-WbHealthJson $HttpResult.Json }
    if (-not $validation.Ok) { return (New-WbServiceState $Definition 'unhealthy' $validation.Reason $owners) }
    return (New-WbServiceState $Definition 'healthy' $validation.Reason $owners)
}

function Get-WbOwnerStamp($Snapshot) {
    return (@($Snapshot.Owners | Sort-Object ProcessId | ForEach-Object { '{0}|{1}|{2}|{3}' -f $_.ProcessId, $_.ExecutablePath, $_.CommandLine, $_.CreationDate }) -join "`n")
}

function Get-WbServiceState($Definition, [int]$TimeoutMs = 2500, [int]$MaxBytes = 32768, [scriptblock]$ListenerProvider, [scriptblock]$HttpProvider) {
    if ($null -eq $ListenerProvider) { $ListenerProvider = { param($port) Get-WbListenerSnapshot $port } }
    if ($null -eq $HttpProvider) { $HttpProvider = { param($uri, $timeout, $maximum) Invoke-WbBoundedJsonGet $uri $timeout $maximum } }
    try {
        $before = & $ListenerProvider $Definition.Port
        $initial = Resolve-WbServiceState $Definition $before $null
        if ($initial.State -ne 'unhealthy') { return $initial }
        $http = & $HttpProvider ('http://127.0.0.1:{0}{1}' -f $Definition.Port, $Definition.Endpoint) $TimeoutMs $MaxBytes
        $after = & $ListenerProvider $Definition.Port
        if (-not $after.Success -or (Get-WbOwnerStamp $before) -cne (Get-WbOwnerStamp $after)) {
            return (New-WbServiceState $Definition 'error' 'listener-owner-changed-during-http-check' @($before.Owners))
        }
        return (Resolve-WbServiceState $Definition $after $http)
    } catch { return (New-WbServiceState $Definition 'error' 'service-inspection-exception') }
}

function Wait-WbServiceState($Definition, [int]$Seconds = 20, [int]$TimeoutMs = 2500, [int]$MaxBytes = 32768) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $remainingMs = ($Seconds * 1000) - [int]$timer.ElapsedMilliseconds
        $budget = $TimeoutMs
        if ($Seconds -gt 0) { $budget = [Math]::Max(100, [Math]::Min($TimeoutMs, $remainingMs)) }
        $result = Get-WbServiceState $Definition $budget $MaxBytes
        if ($result.State -in @('healthy', 'foreign', 'error') -or $Seconds -eq 0 -or $timer.Elapsed.TotalSeconds -ge $Seconds) { return $result }
        Start-Sleep -Milliseconds 250
    } while ($true)
}

if ($MyInvocation.InvocationName -ne '.' -and -not $Library) {
    $definition = @(Get-WbServiceDefinitions $PanelRoot | Where-Object { $_.Name -eq $Service })[0]
    $result = Wait-WbServiceState $definition $WaitSeconds $TimeoutMs $MaxBytes
    Write-Output ('WB_CHECK name={0} port={1} state={2} reason={3} pids={4}' -f $result.Name, $result.Port, $result.State, $result.Reason, ($result.OwnerPids -join ','))
    exit ([int]$result.ExitCode)
}
