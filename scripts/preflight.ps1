[CmdletBinding()]
param([switch]$Quick)
$ErrorActionPreference='Stop'
$r=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $r
try {
    $dirty=git status --porcelain -uall
    if ($LASTEXITCODE -ne 0 -or $dirty) {throw '工作树或暂存区不干净'}
    $files=@(git -c core.quotepath=false ls-files)
    if ($LASTEXITCODE -ne 0) {throw 'Git 文件清单失败'}
    $bad=$files | Where-Object {$_ -match '(^|/)baseline/|\.apk$|\.(jks|keystore|pem|pfx|key)$|(^|/)(keystore\.properties|\.cardback_token|\.env)$|^tools/(rewrite-service/data|cardback-editor/(published|\.cardback_tls))/'}
    if ($bad) {throw "禁止跟踪：$($bad -join ', ')"}
    $pattern='BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|sk-[A-Za-z0-9_-]{24,}|Bearer [A-Za-z0-9_.-]{32,}|(api_key|access_token|storePassword|keyPassword)[[:space:]]*[:=][[:space:]]*["''][A-Za-z0-9+/_.-]{20,}["'']'
    $hits=git grep --cached -I -i -l -E $pattern --
    if ($LASTEXITCODE -eq 0) {throw "敏感内容命中：$($hits -join ', ')"}
    if ($LASTEXITCODE -ne 1) {throw '扫描失败'}
    $count=0
    foreach ($file in $files | Where-Object {$_ -match '\.(kt|py)$'}) {
        $lines=[IO.File]::ReadAllLines((Join-Path $r $file)).Length
        if ($lines -gt 450) {Write-Output "$lines $file"; $count++}
    }
    Write-Output "超过450行：$count"
    $android=Join-Path $r 'app/src_repo'
    $key=Join-Path $r '_toolchain/signing/wordhelper.jks'
    $props=Join-Path $android 'app/keystore.properties'
    $jdk=((Get-Content "$android/gradle.properties" | Where-Object {$_ -like 'org.gradle.java.home=*'}) -split '=',2)[1]
    $saved=$env:WORDHELPER_STORE_PASS
    try {
        $env:WORDHELPER_STORE_PASS=((Get-Content -LiteralPath $props | Where-Object {$_ -match '^\s*storePassword\s*='}) -split '=',2)[1].Trim()
        $cert=& "$jdk/bin/keytool.exe" -list -v -alias wordhelper -keystore $key -storepass:env WORDHELPER_STORE_PASS 2>&1
        if ($LASTEXITCODE -ne 0 -or (($cert -join ' ') -replace ':','') -notmatch '963533138ed73e92e9a2859f80fdf18becb4ee5a83132c55634b965d7164ba1c') {throw '签名材料校验失败'}
    } finally {$env:WORDHELPER_STORE_PASS=$saved}
    if ($Quick) {Write-Output 'Quick PASS'; return}
    $tmp=Join-Path $r ('.preflight-'+[guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    $oldGradle=$env:GRADLE_USER_HOME
    $oldJava=$env:JAVA_HOME
    $oldData=$env:REWRITE_DATA_DIR
    try {
        $env:JAVA_HOME=$jdk
        $env:GRADLE_USER_HOME=Join-Path $tmp 'gradle-home'
        foreach ($name in 'caches','init.d') {
            robocopy "$r/_toolchain/gradle-home/$name" "$env:GRADLE_USER_HOME/$name" /E /XJ /R:0 /W:0 /XF *.lock /NFL /NDL /NJH /NJS /NP | Out-Null
            if ($LASTEXITCODE -ge 8) {throw '复制离线缓存失败'}
        }
        & "$r/_toolchain/gradle-8.9/bin/gradle.bat" -p $android --offline --no-daemon --no-build-cache :app:testProdDebugUnitTest --rerun :app:testDevDebugUnitTest --rerun
        if ($LASTEXITCODE -ne 0) {throw 'Gradle 单测失败'}
        foreach ($v in 'Prod','Dev') {
            $n=$failed=$skipped=0
            foreach ($xml in Get-ChildItem "$android/app/build/test-results/test$($v)DebugUnitTest/TEST-*.xml") {
                $s=([xml](Get-Content -Raw -LiteralPath $xml.FullName)).testsuite
                $n+=[int]$s.tests
                $failed+=[int]$s.errors+[int]$s.failures
                $skipped+=[int]$s.skipped
            }
            Write-Output "$v JVM tests=$n failed=$failed skipped=$skipped"
            if (!$n -or $failed -or $skipped) {throw 'JVM 结果不合格'}
        }
        $iso=Join-Path $tmp 'source'
        foreach ($rel in $files | Where-Object {$_ -match '^(tools|content|docs/testdata)/'}) {
            $dst=Join-Path $iso $rel
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($dst)) | Out-Null
            [IO.File]::Copy((Join-Path $r $rel),$dst)
        }
        $env:REWRITE_DATA_DIR=Join-Path $tmp 'rewrite-data'
        Push-Location $iso
        try {
            foreach ($suite in 'tools/rewrite-service/tests','tools/word-audit/tests') {
                python -B -X utf8 -m unittest discover -s $suite -p 'test*.py'
                if ($LASTEXITCODE -ne 0) {throw "后端单测失败 $suite"}
            }
            $scripts = @('tools/rewrite-service/test_unverified.py') + @(
                Get-ChildItem 'tools/cardback-editor/test*.py' | ForEach-Object { $_.FullName }
            )
            foreach ($script in $scripts) {
                Write-Output ("Backend " + (Split-Path $script -Leaf))
                python -B -X utf8 $script
                if ($LASTEXITCODE -ne 0) {throw "后端验收失败：$(Split-Path $script -Leaf)"}
            }
        } finally {Pop-Location}
        Write-Output 'Full PASS'
    } finally {
        $env:JAVA_HOME=$oldJava
        $env:GRADLE_USER_HOME=$oldGradle
        $env:REWRITE_DATA_DIR=$oldData
        if ((Split-Path (Resolve-Path -LiteralPath $tmp).Path) -ne $r -or (Split-Path $tmp -Leaf) -notmatch '^\.preflight-[a-f0-9]{32}$') {throw '临时目录边界异常'}
        Remove-Item -LiteralPath $tmp -Recurse -Force
    }
} finally {Pop-Location}
