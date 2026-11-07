"""Independent G3 CMD control-flow test with system PowerShell and safe adapters.
The single hardcoded sanitizer line is redirected to a local argv/exit recorder.
The helper target is a high-port bridge importing byte-identical original helper.
Every other CMD byte remains frozen, and full source/adapted hashes are recorded.
"""
import importlib.util,json,os,shutil,subprocess,sys,time
from pathlib import Path
sys.dont_write_bytecode=True
p=Path(__file__).resolve().parent
sp=importlib.util.spec_from_file_location('g3_system_harness',p/'g34-independent.py')
m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m)
report={'hashesBegin':m.hashes(),'transformations':[]}

def adapt(s):
    original=(m.CANDIDATE/'start-silent.cmd').read_bytes()
    source=original.decode('ascii')
    lines=[line for line in source.splitlines(keepends=True) if 'wb-config-sanitize.ps1' in line]
    assert len(lines)==1
    new_line='powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0independent-sanitize-recorder.ps1" -Dir "%~dp0." -LogPath "%~dp0independent-sanitize.log" -Quiet'
    if lines[0].endswith('\r\n'):new_line+='\r\n'
    elif lines[0].endswith('\n'):new_line+='\n'
    adapted=source.replace(lines[0],new_line)
    assert adapted.replace(new_line,lines[0]).encode('ascii')==original
    (s.dir/'run/start-silent.cmd').write_bytes(adapted.encode('ascii'))
    helper=s.dir/'run/wb-service-check.ps1'
    helper_original=helper.read_bytes();(s.dir/'run/wb-service-check-original.ps1').write_bytes(helper_original)
    assert m.hashlib.sha256(helper_original).hexdigest()==m.EXPECTED['candidate/wb-service-check.ps1']
    bridge=(p/'g34-cli-bridge.ps1').read_text(encoding='utf-8')
    bridge=bridge.replace('[Parameter(Mandatory=$true)][string]$IndependentTargetHelper',"[string]$IndependentTargetHelper = '"+str(s.dir/'run/wb-service-check-original.ps1').replace("'","''")+"'")
    bridge=bridge.replace('[Parameter(Mandatory=$true)][string]$IndependentPortMap',"[string]$IndependentPortMap = '"+str(s.dir/'port-map.json').replace("'","''")+"'")
    bridge=bridge.replace('$requestedWait = $WaitSeconds',"$requestedWait = $WaitSeconds\n$trace = [pscustomobject]@{service=$selectedService;root=$selectedRoot;waitSeconds=$requestedWait} | ConvertTo-Json -Compress\n[IO.File]::AppendAllText((Join-Path $PSScriptRoot '..\\helper-calls.jsonl'),$trace+[Environment]::NewLine,(New-Object Text.UTF8Encoding($false)))")
    helper.write_text(bridge,encoding='utf-8')
    recorder='''[CmdletBinding()]
param([string]$Dir,[string]$LogPath,[switch]$Quiet)
$control=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'shim-control.json')) | ConvertFrom-Json
$entry=[pscustomobject]@{kind='isolated-sanitizer-recorder';dir=$Dir;logPath=$LogPath;quiet=[bool]$Quiet;exitCode=$control.sanitizeCode}|ConvertTo-Json -Compress
[IO.File]::AppendAllText($control.calls,$entry+[Environment]::NewLine,(New-Object Text.UTF8Encoding($false)))
exit ([int]$control.sanitizeCode)
'''
    (s.dir/'run/independent-sanitize-recorder.ps1').write_text(recorder,encoding='utf-8')
    s.env['PATH']=os.environ.get('PATH','')
    report['transformations'].append({'scenario':s.name,'sourceHash':m.hashlib.sha256(original).hexdigest(),'adaptedHash':m.hashlib.sha256(adapted.encode('ascii')).hexdigest(),'originalLine':lines[0].strip(),'replacementLine':new_line.strip(),'onlyCMDLineChanged':True,'originalHelperHash':m.hashlib.sha256(helper_original).hexdigest(),'helperAdapterHash':m.hashlib.sha256(bridge.encode('utf-8')).hexdigest(),'PowerShell':'native system executable, no PATH shadow'})

def save_calls(s):
    for f in ('helper-calls.jsonl','shim-calls.jsonl'):
        if (s.dir/f).exists():shutil.copy2(s.dir/f,p/('g3-'+s.name+'-'+f))

try:
    m.RUNTIME.mkdir();env={**os.environ,'TEMP':str(m.RUNTIME),'TMP':str(m.RUNTIME)}
    rc,text=m.run([m.CSC,'/nologo','/target:exe','/r:System.Web.Extensions.dll','/out:'+str(m.RUNTIME/'fixture.exe'),p/'IndependentWbFixture.cs'],env=env,label='g3-native-compile')
    if rc:raise RuntimeError(text)
    for state in ('healthy','main-foreign','main-unhealthy','router-foreign','router-missing','hold-main-missing','sanitize-one','sanitize-fail'):
        s=m.Scenario('system-powershell-'+state)
        try:
            adapt(s)
            if state!='hold-main-missing':s.launch('main',foreign=state=='main-foreign')
            if state=='main-unhealthy':s.mode('main','unhealthy')
            if state not in ('main-foreign','main-unhealthy','router-missing','hold-main-missing','sanitize-fail'):s.launch('router',foreign=state=='router-foreign')
            if state=='hold-main-missing':(s.dir/'run/.hold-restart').write_text('independent hold')
            if state.startswith('sanitize-'):
                control=json.loads((s.dir/'run/shim-control.json').read_text());control['sanitizeCode']=1 if state=='sanitize-one' else 2;m.dump(s.dir/'run/shim-control.json',control)
            before={n:len(s.launches(n)) for n in s.ports}
            rc,text=s.cmd();expected=20 if state in ('main-foreign','router-foreign') else 30 if state=='main-unhealthy' else 10 if state=='hold-main-missing' else 40 if state=='sanitize-fail' else 0
            m.check('G3-native-'+state+'-exit',rc==expected,{'expected':expected,'actual':rc,'output':text},'G3')
            if state=='router-missing' and rc==0:s.await_ready('router')
            after={n:len(s.launches(n)) for n in s.ports};wanted={**before}
            if state=='router-missing':wanted['router']=1
            m.check('G3-native-'+state+'-launch-counts',after==wanted,{'before':before,'after':after},'G3')
            if state in ('main-foreign','router-foreign'):m.check('G3-native-'+state+'-explicit-CONFLICT','CONFLICT' in text,{'output':text},'G3')
            if state=='main-unhealthy':m.check('G3-native-correct-unhealthy-report','state=unhealthy' in text,{'output':text},'G3')
            calls=m.read_jsonl(s.dir/'helper-calls.jsonl')
            if state=='router-missing':m.check('G3-native-router-WScript-and-wait20',any(c['service']=='router' and c['waitSeconds']==20 for c in calls) and len(s.launches('router'))==1,calls,'G3')
            if state=='healthy':
                rc2,text2=s.cmd();m.check('G3-native-healthy-repeated-idempotent',rc2==0 and all(len(s.launches(n))==before[n] for n in s.ports),{'rc':rc2,'output':text2},'G3')
            save_calls(s)
        finally:s.cleanup()
    s=m.Scenario('system-powershell-main-missing');cmd=None
    try:
        adapt(s);s.launch('router')
        log=(p/'g3-system-powershell-main-missing-output-v2.log').open('xb');s.streams.append(log)
        argv=[str(m.CMD),'/d','/c',str(s.dir/'run/start-silent.cmd')]
        cmd=subprocess.Popen(argv,cwd=s.dir/'run',env=s.env,stdout=log,stderr=subprocess.STDOUT)
        m.OWNED.append({'kind':'owned-cmd','pid':cmd.pid,'argv':argv})
        s.await_ready('main',timeout=35)
        m.check('G3-native-missing-main-starts-once',len(s.launches('main'))==1 and len(s.launches('router'))==1,s.launches('main'),'G3')
        m.check('G3-native-missing-main-preserves-config-and-panel-log',(s.dir/'run/panel.log').exists(),None,'G3')
        s.stop('main');cmd.wait(timeout=10)
        m.check('G3-native-missing-main-propagates-exe-exit',cmd.returncode==0,{'rc':cmd.returncode},'G3')
        m.COMMANDS.append({'label':'native-main-missing','argv':argv,'exitCode':cmd.returncode,'outputFile':str(p/'g3-system-powershell-main-missing-output-v2.log')})
        save_calls(s)
    finally:
        s.cleanup()
        if cmd and cmd.poll() is None:raise RuntimeError('owned cmd still running')
except Exception as exc:
    report['fatal']={'type':type(exc).__name__,'message':str(exc)};m.check('G3-native-batch-completed',False,report['fatal'],'harness')
finally:
    for s in m.SCENARIOS:
        if s.dir.exists():
            try:save_calls(s);s.cleanup()
            except Exception as exc:m.CLEANUP.append({'scenario':s.name,'error':str(exc)})
    closed=all(m.port_closed(port) for s in m.SCENARIOS for port in s.ports.values())
    dead=all(h.poll() is not None for s in m.SCENARIOS for h in s.handles.values())
    m.check('G3-native-all-owned-ports-closed',closed,{'ports':[port for s in m.SCENARIOS for port in s.ports.values()]},'cleanup')
    m.check('G3-native-owned-handles-exited',dead,m.OWNED,'cleanup')
    if closed and dead and not any('error' in x for x in m.CLEANUP):shutil.rmtree(m.RUNTIME)
    m.check('G3-native-scratch-removed',not m.RUNTIME.exists(),str(m.RUNTIME),'cleanup')
    report['hashesEnd']=m.hashes();m.check('G3-native-freeze-exact',report['hashesBegin']==report['hashesEnd']==m.EXPECTED,report['hashesEnd'],'freeze')
    report.update(cases=m.CASES,commands=m.COMMANDS,cleanup=m.CLEANUP,ownedProcesses=m.OWNED,scenarioPorts={s.name:s.ports for s in m.SCENARIOS})
    m.dump(p/'g3-cmd-system-evidence-v2.json',report)
failed=[c for c in m.CASES if not c['passed']]
print(json.dumps({'total':len(m.CASES),'passed':len(m.CASES)-len(failed),'failed':failed,'fatal':report.get('fatal'),'evidence':str(p/'g3-cmd-system-evidence-v2.json')}),flush=True)
sys.exit(0 if not failed else 1)
