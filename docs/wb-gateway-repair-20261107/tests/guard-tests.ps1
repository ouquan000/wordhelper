[CmdletBinding()]
param([string]$CandidateDir = '')
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($CandidateDir)) { $CandidateDir = Join-Path (Split-Path $PSScriptRoot -Parent) 'candidate' }
$expectedSpec = 'c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91'
$expectedLock = '50f75118dffa481cd7299f17c6e2981b7dcc96252261cc1543382428bf0fe88b'
$specDir = Split-Path $PSScriptRoot -Parent
$specPath = Join-Path $specDir 'acceptance-spec.md'
$lockPath = Join-Path $specDir 'acceptance-spec.lock'
$helper = Join-Path $CandidateDir 'wb-service-check.ps1'
$guard = Join-Path $CandidateDir 'wb-gateway-guard.ps1'
$startup = Join-Path $CandidateDir 'start-silent.cmd'
$temp = Join-Path $PSScriptRoot ('guard-tmp-' + [Guid]::NewGuid().ToString('N'))
$fixtureProcess = $null
$checks = New-Object 'System.Collections.Generic.List[object]'
$httpCases = New-Object 'System.Collections.Generic.List[object]'
$cmdCases = New-Object 'System.Collections.Generic.List[object]'
$specBefore = (Get-FileHash -LiteralPath $specPath -Algorithm SHA256).Hash.ToLowerInvariant()
$lockBefore = (Get-FileHash -LiteralPath $lockPath -Algorithm SHA256).Hash.ToLowerInvariant()
$failure = ''

function Assert-Guard([bool]$Condition, [string]$Name) {
    $checks.Add([pscustomobject]@{ Name = $Name; Passed = $Condition })
    if (-not $Condition) { throw ('ASSERT FAILED: ' + $Name) }
}
function Assert-GuardEqual($Actual, $Expected, [string]$Name) { Assert-Guard ($Actual -ceq $Expected) ($Name + ' expected=' + $Expected + ' actual=' + $Actual) }
function New-Snapshot([object[]]$Owners = @(), [bool]$Success = $true) { return [pscustomobject]@{ Success = $Success; Owners = @($Owners) } }
function New-Owner([int]$Id, [string]$Exe, [string]$Line = '') { return [pscustomobject]@{ ProcessId = $Id; ExecutablePath = $Exe; CommandLine = $Line; CreationDate = '2026-11-07T00:00:00Z' } }
function Good-Http($Json) { return [pscustomobject]@{ Ok = $true; StatusCode = 200; Json = $Json; Reason = 'fixture' } }
function Invoke-DecisionCase($Definition, [string]$Initial, [string]$Fresh, [string]$After, [bool]$NoStart = $false, [bool]$Held = $false, [string]$Config = 'ok') {
    $script:decision = @{ CheckCalls = 0; Starts = 0; Waits = 0; Logs = @(); Initial = $Initial; Fresh = $Fresh; After = $After; Held = $Held }
    $check = {
        param($def)
        $script:decision.CheckCalls++
        $name = $script:decision.Initial
        if ($script:decision.CheckCalls -gt 1) { $name = $script:decision.Fresh }
        New-WbServiceState $def $name ('mock-' + $name)
    }
    $heldCheck = { param($def) return $script:decision.Held }
    $starter = { param($def) $script:decision.Starts++ }
    $waiter = { param($def) $script:decision.Waits++; New-WbServiceState $def $script:decision.After ('mock-' + $script:decision.After) }
    $logger = { param($line) $script:decision.Logs += [string]$line }
    $result = Invoke-WbGuardService $Definition $Config $NoStart $check $heldCheck $starter $waiter $logger
    return [pscustomobject]@{ Result = $result; Starts = $script:decision.Starts; Waits = $script:decision.Waits; Logs = ($script:decision.Logs -join "`n") }
}

try {
    Assert-GuardEqual $PSVersionTable.PSVersion.Major 5 'running Windows PowerShell 5.1'
    Assert-GuardEqual $specBefore $expectedSpec 'frozen spec checksum at start'
    Assert-GuardEqual $lockBefore $expectedLock 'frozen lock checksum at start'
    foreach ($path in @($helper, $guard)) {
        $parseTokens = $null; $parseErrors = $null
        $null = [Management.Automation.Language.Parser]::ParseFile($path, [ref]$parseTokens, [ref]$parseErrors)
        Assert-GuardEqual @($parseErrors).Count 0 ('PS5.1 syntax ' + [IO.Path]::GetFileName($path))
    }
    # These tripwires prove that library loading is inert and that mocked tests
    # cannot accidentally inspect the real gateway listeners/process inventory.
    function Start-Process { throw 'UNEXPECTED service launch while loading a library' }
    function Get-NetTCPConnection { throw 'REAL LISTENER QUERY FORBIDDEN in isolated tests' }
    function Get-CimInstance { throw 'REAL PROCESS QUERY FORBIDDEN in isolated tests' }
    . $helper -Library
    . $guard -Library
    Remove-Item Function:Start-Process
    Assert-Guard $true 'dot-source loads definitions without repair/log/launch'
    [void][IO.Directory]::CreateDirectory($temp)
    $mockRoot = Join-Path $temp 'panel with spaces'
    $defs = @(Get-WbServiceDefinitions $mockRoot)
    $main = $defs | Where-Object Name -eq 'main'
    $aux = $defs | Where-Object Name -eq 'aux'
    $router = $defs | Where-Object Name -eq 'router'
    Assert-GuardEqual (($defs.Port | Sort-Object) -join ',') '8788,8789,8790' 'guard covers all three service ports'
    $mainOwner = New-Owner 1101 $main.Executable
    $auxOwner = New-Owner 1102 $aux.Executable
    $nodeExe = 'C:\Program Files\nodejs\node.exe'
    $routerOwner = New-Owner 1103 $nodeExe ('"' + $nodeExe + '" "' + $router.Script + '"')
    $health = ConvertFrom-Json '{"service":"workbuddy2api","healthy":7}'
    Assert-Guard (Test-WbOwnerIdentity $main $mainOwner) 'main exact executable owner accepted'
    Assert-Guard (-not (Test-WbOwnerIdentity $main $auxOwner)) 'main rejects aux executable with same basename'
    Assert-Guard (-not (Test-WbOwnerIdentity $aux $mainOwner)) 'aux rejects main executable with same basename'
    Assert-Guard (-not (Test-WbOwnerIdentity $main (New-Owner 1104 'C:\elsewhere\wb2api.exe'))) 'same basename elsewhere rejected'
    Assert-Guard (-not (Test-WbOwnerIdentity $main (New-Owner 1104 ''))) 'unreadable executable rejected'
    Assert-Guard (Test-WbOwnerIdentity $router $routerOwner) 'router exact node argv script accepted with spaces'
    foreach ($line in @(
        ('"' + $nodeExe + '" "' + $router.Script + '.bak"'),
        ('"' + $nodeExe + '" -e "' + $router.Script + '"'),
        ('"' + $nodeExe + '" "C:\elsewhere\wb-router.js" "' + $router.Script + '"'),
        ('"' + $nodeExe + '" "' + $router.Script)
    )) { Assert-Guard (-not (Test-WbOwnerIdentity $router (New-Owner 1103 $nodeExe $line))) 'router rejects wrong/eval/substring/unbalanced argv' }
    Assert-Guard (-not (Test-WbOwnerIdentity $router (New-Owner 1103 'C:\elsewhere\not-node.exe' $routerOwner.CommandLine))) 'router rejects non-node executable'
    Assert-Guard ((Test-WbHealthJson $health).Ok) 'healthy service JSON accepted'
    foreach ($json in @(
        '{}', '{"service":"other","healthy":7}', '{"service":"WorkBuddy2api","healthy":7}',
        '{"service":"workbuddy2api","healthy":0}', '{"service":"workbuddy2api","healthy":-1}',
        '{"service":"workbuddy2api","healthy":true}', '{"service":"workbuddy2api","healthy":"7"}',
        '{"service":"workbuddy2api","healthy":0.5}', '{"service":"workbuddy2api"}'
    )) { Assert-Guard (-not (Test-WbHealthJson (ConvertFrom-Json $json)).Ok) ('reject invalid health shape ' + $json) }
    $legacyRouter = '{"state":"online","qq":{"raw":null},"aux":{"target":"127.0.0.1:8789","healthy":false,"paused":false},"main":{"target":"127.0.0.1:8790"},"hold":{"onlineMs":180000,"offlineMs":300000},"weight":{"main":5,"aux":3},"counters":{"requests":0,"toMain":0,"toAux":0}}'
    $routerJson = ConvertFrom-Json $legacyRouter
    Assert-Guard ((Test-WbRouterJson $routerJson).Ok) 'legacy router status without main health accepted'
    $routerJson.PSObject.Properties.Remove('main')
    Assert-Guard ((Test-WbRouterJson $routerJson).Ok) 'older status without main object accepted'
    $routerJson = ConvertFrom-Json $legacyRouter
    $routerJson | Add-Member mainOk $false
    $routerJson | Add-Member mainReason 'unhealthy'
    $routerJson | Add-Member mainCheckedAt '2026-11-07T00:00:00.000Z'
    $routerJson.main | Add-Member healthy $false
    $routerJson.main | Add-Member reason 'unhealthy'
    Assert-Guard ((Test-WbRouterJson $routerJson).Ok) 'new status schema accepted; pool failures assessed separately'
    $routerJson.main.target = '127.0.0.1:12345'
    Assert-Guard (-not (Test-WbRouterJson $routerJson).Ok) 'router wrong main target rejected'
    $routerJson = ConvertFrom-Json $legacyRouter
    $routerJson.aux.healthy = 'true'
    Assert-Guard (-not (Test-WbRouterJson $routerJson).Ok) 'router wrong boolean type rejected'
    Assert-Guard (-not (Test-WbRouterJson $health).Ok) 'arbitrary health JSON is not a router status'
    $goodHttp = Good-Http $health
    $snapMain = New-Snapshot @($mainOwner)
    Assert-GuardEqual (Resolve-WbServiceState $main $snapMain $goodHttp).ExitCode 0 'healthy state code'
    Assert-GuardEqual (Resolve-WbServiceState $main (New-Snapshot) $null).ExitCode 10 'missing state code'
    Assert-GuardEqual (Resolve-WbServiceState $main (New-Snapshot @($auxOwner)) $goodHttp).ExitCode 20 'foreign state code'
    Assert-GuardEqual (Resolve-WbServiceState $main $snapMain (Good-Http (ConvertFrom-Json '{"service":"wrong","healthy":7}'))).ExitCode 30 'unhealthy state code'
    $badStatus = Good-Http $health
    $badStatus.StatusCode = 404
    Assert-GuardEqual (Resolve-WbServiceState $main $snapMain $badStatus).ExitCode 30 'pure resolver independently rejects 404 despite JSON identity'
    Assert-GuardEqual (Resolve-WbServiceState $main (New-Snapshot @() $false) $null).ExitCode 40 'failed discovery is not missing'
    Assert-GuardEqual (Resolve-WbServiceState $main (New-Snapshot @($mainOwner, $auxOwner)) $goodHttp).State 'foreign' 'mixed listener owners fail closed'
    $result = Get-WbServiceState $main -ListenerProvider { param($p) New-Snapshot @($auxOwner) } -HttpProvider { throw 'foreign must never cause an HTTP call' }
    Assert-GuardEqual $result.State 'foreign' 'foreign process detected before any HTTP request'
    $result = Get-WbServiceState $main -ListenerProvider { param($p) $snapMain } -HttpProvider { param($u, $t, $m) $goodHttp }
    Assert-GuardEqual $result.State 'healthy' 'orchestrated exact owner plus healthy JSON'
    $script:identityCalls = 0
    $result = Get-WbServiceState $main -ListenerProvider { param($p) $script:identityCalls++; if ($script:identityCalls -eq 1) { $snapMain } else { New-Snapshot @($auxOwner) } } -HttpProvider { param($u, $t, $m) $goodHttp }
    Assert-GuardEqual $result.ExitCode 40 'owner changes during HTTP cannot count as healthy'
    foreach ($def in $defs) {
        foreach ($stateName in @('healthy', 'foreign', 'unhealthy', 'error')) {
            $case = Invoke-DecisionCase $def $stateName $stateName 'healthy'
            Assert-GuardEqual $case.Starts 0 ($def.Name + ' existing ' + $stateName + ' never starts')
            Assert-GuardEqual $case.Waits 0 ($def.Name + ' existing ' + $stateName + ' never recovery-polls')
            if ($stateName -eq 'foreign') { Assert-Guard ($case.Logs -match 'CONFLICT') ($def.Name + ' foreign explicitly logged') }
            if ($stateName -eq 'unhealthy') { Assert-Guard ($case.Logs -match 'WARN UNHEALTHY') ($def.Name + ' unhealthy explicitly logged') }
        }
        $case = Invoke-DecisionCase $def 'missing' 'missing' 'healthy' $true
        Assert-GuardEqual $case.Starts 0 ($def.Name + ' NoRestart prevents launch')
        Assert-GuardEqual $case.Result.ExitCode 10 ($def.Name + ' NoRestart missing is nonzero')
        $case = Invoke-DecisionCase $def 'missing' 'missing' 'healthy' $false $true
        Assert-GuardEqual $case.Starts 0 ($def.Name + ' hold prevents launch')
        $case = Invoke-DecisionCase $def 'missing' 'foreign' 'healthy'
        Assert-GuardEqual $case.Starts 0 ($def.Name + ' new foreign before launch prevents start')
        Assert-GuardEqual $case.Result.ExitCode 20 ($def.Name + ' new foreign before launch nonzero')
        $case = Invoke-DecisionCase $def 'missing' 'missing' 'healthy'
        Assert-GuardEqual $case.Starts 1 ($def.Name + ' missing recovers exactly once')
        Assert-GuardEqual $case.Waits 1 ($def.Name + ' recovery verifies identity/HTTP')
        Assert-GuardEqual $case.Result.ExitCode 0 ($def.Name + ' verified recovery healthy')
        foreach ($afterName in @('foreign', 'unhealthy', 'missing', 'error')) {
            $case = Invoke-DecisionCase $def 'missing' 'missing' $afterName
            Assert-Guard ($case.Result.ExitCode -ne 0) ($def.Name + ' post-recovery ' + $afterName + ' is nonzero')
            Assert-GuardEqual $case.Starts 1 ($def.Name + ' no retry loop over post-recovery ' + $afterName)
        }
    }
    $case = Invoke-DecisionCase $main 'missing' 'missing' 'healthy' $false $false 'broken'
    Assert-GuardEqual $case.Starts 0 'broken config blocks missing service recovery'
    $configDir = Join-Path $temp 'config-fixture'
    [void][IO.Directory]::CreateDirectory($configDir)
    $configPath = Join-Path $configDir 'config.json'
    $bakPath = Join-Path $configDir 'config.json.bak'
    $plain = [Text.Encoding]::UTF8.GetBytes('{"fixture":true}')
    [IO.File]::WriteAllBytes($configPath, ([byte[]](0xEF,0xBB,0xBF) + $plain))
    $nullLog = { param($m) }
    Assert-GuardEqual (Repair-WbGuardConfig $configDir $nullLog) 'ok' 'isolated BOM config safely repaired'
    Assert-GuardEqual ([Convert]::ToBase64String([IO.File]::ReadAllBytes($configPath))) ([Convert]::ToBase64String($plain)) 'BOM removed without changing JSON bytes'
    Assert-GuardEqual ((Get-FileHash $configPath).Hash) ((Get-FileHash $bakPath).Hash) 'literal good backup matches config'
    Assert-GuardEqual @(Get-ChildItem -LiteralPath $configDir -Filter 'config.json.bak-bom-*').Count 1 'original BOM bytes backed up'
    $bakHash = (Get-FileHash $bakPath).Hash
    [IO.File]::WriteAllBytes($configPath, ([byte[]](0xEF,0xBB,0xBF) + [Text.Encoding]::UTF8.GetBytes('{broken')))
    $brokenHash = (Get-FileHash $configPath).Hash
    Assert-GuardEqual (Repair-WbGuardConfig $configDir $nullLog) 'broken' 'invalid config reported'
    Assert-GuardEqual (Get-FileHash $bakPath).Hash $bakHash 'invalid JSON preserves valid backup'
    Assert-GuardEqual (Get-FileHash $configPath).Hash $brokenHash 'invalid JSON including BOM left unchanged'
    $watchScript = 'D:\fixture\wb-bom-watch.ps1'
    $psExe = 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
    Assert-Guard (Test-WbWatchOwner (New-Owner 1 $psExe ('"' + $psExe + '" -NoProfile -File "' + $watchScript + '"')) $watchScript) 'watcher exact script identity accepted'
    Assert-Guard (Test-WbWatchOwner (New-Owner 1 $psExe ('powershell.exe -NoProfile -File "' + $watchScript + '"')) $watchScript) 'watcher exact script with bare executable argv accepted'
    Assert-Guard (-not (Test-WbWatchOwner (New-Owner 1 $psExe ('"' + $psExe + '" -Command echo -File "' + $watchScript + '"')) $watchScript)) 'watcher rejects path mentioned inside command'
    Assert-Guard (-not (Test-WbWatchOwner (New-Owner 1 $psExe ('"' + $psExe + '" -File "D:\other\wb-bom-watch.ps1"')) $watchScript)) 'reused watcher PID with wrong script rejected'
    $script:watchStarts = 0
    $watchStarter = { $script:watchStarts++ }
    $watchCode = Invoke-WbGuardWatch $guard $true { [pscustomobject]@{ State = 'missing'; ProcessId = 0 } } $watchStarter $nullLog
    Assert-GuardEqual $watchStarts 0 'NoRestart also prevents BOM watcher launch'
    Assert-GuardEqual $watchCode 10 'missing watcher under NoRestart nonzero'
    $watchCode = Invoke-WbGuardWatch $guard $false { [pscustomobject]@{ State = 'error'; ProcessId = 0 } } $watchStarter $nullLog
    Assert-GuardEqual $watchStarts 0 'watch inspection failure never launches'
    $watchCode = Invoke-WbGuardWatch $guard $false { [pscustomobject]@{ State = 'healthy'; ProcessId = 1 } } $watchStarter $nullLog
    Assert-GuardEqual $watchStarts 0 'healthy watcher never duplicated'
    $watchCode = Invoke-WbGuardWatch $guard $false { [pscustomobject]@{ State = 'missing'; ProcessId = 0 } } $watchStarter $nullLog
    Assert-GuardEqual $watchStarts 1 'missing watcher uses one launch callback'
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class WBGuardNativeArgv {
  [DllImport("shell32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
  static extern IntPtr CommandLineToArgvW(string command, out int count);
  [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr block);
  public static string[] Split(string command) {
    int count; IntPtr block = CommandLineToArgvW(command, out count);
    if (block == IntPtr.Zero) throw new Exception("native argv parse failed");
    try { string[] result = new string[count]; for(int i=0;i<count;i++) result[i]=Marshal.PtrToStringUni(Marshal.ReadIntPtr(block, i*IntPtr.Size)); return result; }
    finally { LocalFree(block); }
  }
}
'@
    foreach ($line in @(
        ('"' + $nodeExe + '" "D:\path with spaces\\"'),
        ('"' + $nodeExe + '" "D:\path with \"quoted\" text\wb-router.js"'),
        ('"' + $nodeExe + '" "D:\path\\\\" ""'),
        ('"' + $nodeExe + '" "' + $router.Script + '"')
    )) {
        $ours = @(Split-WbCommandLine $line)
        $native = [WBGuardNativeArgv]::Split($line)
        Assert-GuardEqual ($ours -join "`n") ($native -join "`n") 'tokenizer agrees with Windows CommandLineToArgvW for real slash/quote grammar'
    }
    $waitRoot = Join-Path $temp 'wait-import-panel'
    $waitRun = Join-Path $waitRoot 'run'
    [void][IO.Directory]::CreateDirectory($waitRun)
    [IO.File]::WriteAllText((Join-Path $waitRun 'start-silent.vbs'), 'fixture only; never executed')
    $waitHelper = Join-Path $temp 'guard-wait-helper.ps1'
    [IO.File]::WriteAllText($waitHelper, @'
param([switch]$Library, [int]$WaitSeconds = 0)
function Get-WbServiceDefinitions([string]$Root) {
    [pscustomobject]@{ Name='main'; Port=8790; Dir=(Join-Path $Root 'run'); Starter='start-silent.vbs'; NeedsConfig=$false }
}
function Get-WbServiceState($Definition) { New-WbServiceState $Definition 'missing' 'fixture-no-listener' }
function Wait-WbServiceState($Definition, [int]$Seconds) {
    [IO.File]::WriteAllText((Join-Path $Definition.Dir 'observed-wait.txt'), [string]$Seconds)
    New-WbServiceState $Definition 'healthy' 'fixture-restored'
}
'@, (New-Object Text.UTF8Encoding($false)))
    $script:importStarts = 0
    function Start-Process { $script:importStarts++ }
    try {
        $importReport = Invoke-WbGatewayGuard -Root $waitRoot -SharedHelper $waitHelper -LogFile (Join-Path $temp 'guard-import.log') -BomWatchScript $guard -BomWatchPid (Join-Path $temp 'missing-watch.pid') -RecoveryWaitSeconds 17 -Silent $true
        Assert-GuardEqual ([IO.File]::ReadAllText((Join-Path $waitRun 'observed-wait.txt'))) '17' 'helper default WaitSeconds=0 cannot clobber configured recovery wait=17'
        Assert-GuardEqual $importStarts 1 'full isolated guard executes only the one mocked missing starter'
        Assert-GuardEqual $importReport.Services[0].Action 'recovered' 'full guard recovery reaches verified healthy state'
    } finally { Remove-Item Function:Start-Process }
    # Native CMD integration uses a compiled LOCAL powershell.exe shim. It reads
    # only fixture environment variables; no real sanitizer/helper/binary executes.
    $run = Join-Path $temp 'cmd panel with spaces\run'
    [void][IO.Directory]::CreateDirectory($run)
    Copy-Item -LiteralPath $startup -Destination (Join-Path $run 'start-silent.cmd')
    Copy-Item -LiteralPath $helper -Destination (Join-Path $run 'wb-service-check.ps1')
    $shimSource = @'
using System;
using System.IO;
using System.Diagnostics;
public static class WBGuardShim {
  public static int Main(string[] args) {
    string dir = Path.GetDirectoryName(Process.GetCurrentProcess().MainModule.FileName);
    string exe = Path.GetFileName(Process.GetCurrentProcess().MainModule.FileName);
    string tag = exe.Equals("wb2api.exe", StringComparison.OrdinalIgnoreCase) ? "BINARY" : "PS";
    File.AppendAllText(Path.Combine(dir, "calls.log"), tag + "|" + String.Join("|", args) + Environment.NewLine);
    if (tag == "BINARY") return Int32.Parse(Environment.GetEnvironmentVariable("WB_BINARY_RC") ?? "0");
    string file = "", service = "";
    for (int i=0; i<args.Length-1; i++) {
      if (args[i].Equals("-File", StringComparison.OrdinalIgnoreCase)) file = Path.GetFileName(args[i+1]);
      if (args[i].Equals("-Service", StringComparison.OrdinalIgnoreCase)) service = args[i+1];
    }
    if (file == "wb-config-sanitize.ps1") return Int32.Parse(Environment.GetEnvironmentVariable("WB_SANITIZE_RC") ?? "0");
    if (file != "wb-service-check.ps1" || (service != "main" && service != "router")) return 40;
    string countPath = Path.Combine(dir, service + ".count");
    int count = File.Exists(countPath) ? Int32.Parse(File.ReadAllText(countPath)) : 0;
    File.WriteAllText(countPath, (count+1).ToString());
    string[] values = (Environment.GetEnvironmentVariable(service == "main" ? "WB_MAIN_SEQUENCE" : "WB_ROUTER_SEQUENCE") ?? "40").Split(',');
    return Int32.Parse(values[Math.Min(count, values.Length-1)]);
  }
}
'@
    Add-Type -TypeDefinition $shimSource -OutputAssembly (Join-Path $run 'powershell.exe') -OutputType ConsoleApplication
    Copy-Item -LiteralPath (Join-Path $run 'powershell.exe') -Destination (Join-Path $run 'wb2api.exe')
    $oldEnv = @{}
    foreach ($key in @('WB_MAIN_SEQUENCE','WB_ROUTER_SEQUENCE','WB_SANITIZE_RC','WB_BINARY_RC')) { $oldEnv[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
    try {
        $cmdMatrix = @(
            @{ Name='healthy-idempotent'; Main='0'; Router='0'; Sanitize='0'; Binary='0'; Hold=$false; Expected=0; RunsBinary=$false },
            @{ Name='foreign-main'; Main='20'; Router='0'; Sanitize='0'; Binary='0'; Hold=$false; Expected=20; RunsBinary=$false },
            @{ Name='unhealthy-main'; Main='30'; Router='0'; Sanitize='0'; Binary='0'; Hold=$false; Expected=30; RunsBinary=$false },
            @{ Name='missing-main-foreign-router'; Main='10'; Router='20'; Sanitize='0'; Binary='0'; Hold=$false; Expected=20; RunsBinary=$false },
            @{ Name='healthy-main-unhealthy-router'; Main='0'; Router='30'; Sanitize='0'; Binary='0'; Hold=$false; Expected=30; RunsBinary=$false },
            @{ Name='missing-main-starts-once'; Main='10,10'; Router='0'; Sanitize='0'; Binary='42'; Hold=$false; Expected=42; RunsBinary=$true },
            @{ Name='missing-main-races-foreign'; Main='10,20'; Router='0'; Sanitize='0'; Binary='0'; Hold=$false; Expected=20; RunsBinary=$false },
            @{ Name='sanitizer-repaired-code-one'; Main='0'; Router='0'; Sanitize='1'; Binary='0'; Hold=$false; Expected=0; RunsBinary=$false },
            @{ Name='sanitizer-missing-config'; Main='0'; Router='0'; Sanitize='2'; Binary='0'; Hold=$false; Expected=40; RunsBinary=$false },
            @{ Name='hold-missing-main'; Main='10'; Router='0'; Sanitize='0'; Binary='0'; Hold=$true; Expected=10; RunsBinary=$false },
            @{ Name='hold-healthy-main-router'; Main='0'; Router='0'; Sanitize='0'; Binary='0'; Hold=$true; Expected=0; RunsBinary=$false },
            @{ Name='hold-missing-router'; Main='0'; Router='10'; Sanitize='0'; Binary='0'; Hold=$true; Expected=10; RunsBinary=$false }
        )
        foreach ($item in $cmdMatrix) {
            foreach ($name in @('calls.log','main.count','router.count','.hold-restart')) {
                $target = Join-Path $run $name
                if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force }
            }
            if ($item.Hold) { [IO.File]::WriteAllText((Join-Path $run '.hold-restart'), 'fixture') }
            $env:WB_MAIN_SEQUENCE=$item.Main; $env:WB_ROUTER_SEQUENCE=$item.Router; $env:WB_SANITIZE_RC=$item.Sanitize; $env:WB_BINARY_RC=$item.Binary
            $startInfo = New-Object Diagnostics.ProcessStartInfo
            $startInfo.FileName = $env:ComSpec
            $startInfo.Arguments = '/d /c call "' + (Join-Path $run 'start-silent.cmd') + '"'
            $startInfo.WorkingDirectory = $run
            $startInfo.UseShellExecute = $false
            $startInfo.CreateNoWindow = $true
            $startInfo.RedirectStandardOutput = $true
            $startInfo.RedirectStandardError = $true
            $cmdProcess = New-Object Diagnostics.Process
            $cmdProcess.StartInfo = $startInfo
            [void]$cmdProcess.Start()
            if (-not $cmdProcess.WaitForExit(10000)) { $cmdProcess.Kill(); $cmdProcess.WaitForExit(); throw 'isolated cmd fixture timeout' }
            $lines = @($cmdProcess.StandardOutput.ReadToEnd(), $cmdProcess.StandardError.ReadToEnd())
            $code = $cmdProcess.ExitCode
            $cmdProcess.Dispose()
            $calls = [IO.File]::ReadAllText((Join-Path $run 'calls.log'))
            Assert-GuardEqual $code $item.Expected ('native cmd ' + $item.Name + ' exit')
            Assert-GuardEqual ($calls -match '(?m)^BINARY\|') $item.RunsBinary ('native cmd ' + $item.Name + ' binary decision')
            if ($item.RunsBinary) { Assert-Guard ($calls -match 'BINARY\|-config\|config.json') 'native cmd preserves exact config argument' }
            $cmdCases.Add([pscustomobject]@{ Name=$item.Name; ExitCode=$code; BinaryLaunched=($calls -match '(?m)^BINARY\|'); Output=($lines -join "`n") })
        }
    } finally { foreach ($key in $oldEnv.Keys) { [Environment]::SetEnvironmentVariable($key, $oldEnv[$key], 'Process') } }
    $manifest = Join-Path $temp 'http-manifest.json'
    $fixture = Join-Path $PSScriptRoot 'guard-http-fixture.js'
    $nodePath = (Get-Command node.exe -ErrorAction Stop).Source
    $fixtureProcess = Microsoft.PowerShell.Management\Start-Process -FilePath $nodePath -ArgumentList @(('"' + $fixture + '"'), ('"' + $manifest + '"')) -PassThru -WindowStyle Hidden
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    while (-not (Test-Path -LiteralPath $manifest) -and [DateTime]::UtcNow -lt $deadline -and -not $fixtureProcess.HasExited) { Start-Sleep -Milliseconds 100 }
    Assert-Guard (Test-Path -LiteralPath $manifest) 'HTTP fixture started on OS-assigned port'
    $meta = ConvertFrom-Json ([IO.File]::ReadAllText($manifest))
    Assert-Guard ($meta.port -notin @(8788,8789,8790)) 'HTTP fixture port outside real gateway ports'
    $base = 'http://127.0.0.1:' + $meta.port
    foreach ($path in @('/ok','/wrong','/zero','/malformed','/array','/html','/redirect','/not-found','/oversize-length','/oversize-chunked','/slow-headers','/drip','/truncated')) {
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $http = Invoke-WbBoundedJsonGet ($base + $path) 400 4096
        $elapsed = [int]$timer.ElapsedMilliseconds
        if ($path -eq '/ok') { Assert-Guard ($http.Ok -and (Test-WbHealthJson $http.Json).Ok) 'HTTP correct bounded JSON healthy' }
        elseif ($path -in @('/wrong','/zero')) { Assert-Guard ($http.Ok -and -not (Test-WbHealthJson $http.Json).Ok) ('HTTP identity/count validation ' + $path) }
        else { Assert-Guard (-not $http.Ok) ('HTTP reject ' + $path) }
        if ($path -eq '/not-found') {
            Assert-GuardEqual $http.StatusCode 404 'HTTP 404 status survives PowerShell exception wrapper'
            Assert-GuardEqual $http.Reason 'http-404' 'HTTP 404 has explicit diagnostic reason'
        }
        Assert-Guard ($elapsed -lt 2000) ('HTTP finite deadline ' + $path)
        $httpCases.Add([pscustomobject]@{ Path=$path; Ok=$http.Ok; Reason=$http.Reason; StatusCode=$http.StatusCode; ElapsedMs=$elapsed })
    }
} catch {
    $failure = $_.Exception.Message
    Write-Output ('FAILED ' + $failure)
} finally {
    if ($null -ne $fixtureProcess -and -not $fixtureProcess.HasExited) {
        # This Process instance was returned by this test's own Start-Process.
        $fixtureProcess.Kill()
        [void]$fixtureProcess.WaitForExit(5000)
    }
    $cleanup = $true
    if (Test-Path -LiteralPath $temp) {
        $resolved = [IO.Path]::GetFullPath($temp)
        if (-not $resolved.StartsWith(([IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\') + '\guard-tmp-'), [StringComparison]::OrdinalIgnoreCase)) { throw 'refusing unexpected cleanup path' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
        $cleanup = -not (Test-Path -LiteralPath $resolved)
    }
    $specAfter = (Get-FileHash -LiteralPath $specPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $lockAfter = (Get-FileHash -LiteralPath $lockPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $unchanged = ($specBefore -eq $specAfter -and $specAfter -eq $expectedSpec -and $lockBefore -eq $lockAfter -and $lockAfter -eq $expectedLock)
    $passed = ($failure -eq '' -and $cleanup -and $unchanged)
    $results = [pscustomobject]@{
        Passed=$passed; Failure=$failure; AssertionCount=$checks.Count; PowerShellVersion=$PSVersionTable.PSVersion.ToString();
        FrozenSpecBefore=$specBefore; FrozenSpecAfter=$specAfter; FrozenLockBefore=$lockBefore; FrozenLockAfter=$lockAfter;
        FrozenUnchanged=$unchanged; CleanupComplete=$cleanup; FixtureProcessExited=($null -eq $fixtureProcess -or $fixtureProcess.HasExited);
        Checks=@($checks.ToArray()); CmdCases=@($cmdCases.ToArray()); HttpCases=@($httpCases.ToArray())
    }
    $resultPath = Join-Path $PSScriptRoot 'guard-test-results.json'
    [IO.File]::WriteAllText($resultPath, ($results | ConvertTo-Json -Depth 12), (New-Object Text.UTF8Encoding($false)))
    Write-Output ('GUARD_TESTS passed={0} assertions={1} cmdCases={2} httpCases={3} cleanup={4} frozenUnchanged={5}' -f $passed, $checks.Count, $cmdCases.Count, $httpCases.Count, $cleanup, $unchanged)
}
if (-not $passed) { exit 1 }
exit 0
