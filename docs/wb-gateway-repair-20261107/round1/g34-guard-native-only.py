"""Independent native full-guard batch, separated from CMD shim diagnostics."""
import importlib.util, json, os, shutil, subprocess, sys, time
from pathlib import Path
sys.dont_write_bytecode=True
p=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('g34_native_guard_harness',p/'g34-independent.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
watch=None;stream=None
report={'hashesBegin':m.hashes(),'scope':'Full frozen guard plus original helper; native listener/CIM/HTTP and isolated real WScript recovery; only port definitions and wait trace adapted'}
try:
    m.RUNTIME.mkdir(); env={**os.environ,'TEMP':str(m.RUNTIME),'TMP':str(m.RUNTIME)}
    rc,out=m.run([m.CSC,'/nologo','/target:exe','/r:System.Web.Extensions.dll','/out:'+str(m.RUNTIME/'fixture.exe'),m.ROOT/'IndependentWbFixture.cs'],env=env,label='native-guard-compile')
    if rc:raise RuntimeError(out)
    wp=m.RUNTIME/'independent-watch.ps1'
    wp.write_text('[IO.File]::WriteAllText((Join-Path $PSScriptRoot "watch.pid"),[string]$PID)\n$deadline=[DateTime]::UtcNow.AddMinutes(8)\nwhile(-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot "watch.stop")) -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 100}\n',encoding='utf-8')
    stream=(p/'g34-guard-only-watch-v7.log').open('xb')
    argv=[str(m.PS),'-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',str(wp)]
    watch=subprocess.Popen(argv,env=env,stdout=stream,stderr=subprocess.STDOUT)
    m.OWNED.append({'kind':'watch-fixture','pid':watch.pid,'argv':argv})
    deadline=time.monotonic()+10
    while not (m.RUNTIME/'watch.pid').exists() and time.monotonic()<deadline and watch.poll() is None:time.sleep(.05)
    if not (m.RUNTIME/'watch.pid').exists():raise RuntimeError('isolated watcher did not start')
    m.test_guard(only_pending='hold-only')
except Exception as exc:
    report['fatal']={'type':type(exc).__name__,'message':str(exc)}
    m.check('guard-native-batch-completed',False,report['fatal'],'harness')
finally:
    for s in m.SCENARIOS:
        if s.dir.exists():
            try:s.cleanup()
            except Exception as exc:m.CLEANUP.append({'scenario':s.name,'error':str(exc)})
    if watch:
        (m.RUNTIME/'watch.stop').write_text('owned watcher stop')
        try:watch.wait(timeout=10)
        except subprocess.TimeoutExpired:report['watchDidNotStop']=True
        m.CLEANUP.append({'name':'watch','pid':watch.pid,'exitCode':watch.returncode})
    if stream:stream.close()
    all_closed=all(m.port_closed(port) for s in m.SCENARIOS for port in s.ports.values())
    handles_dead=all(h.poll() is not None for s in m.SCENARIOS for h in s.handles.values()) and (watch is None or watch.poll() is not None)
    m.check('guard-native-all-owned-ports-closed',all_closed,{'ports':[port for s in m.SCENARIOS for port in s.ports.values()]},'cleanup')
    m.check('guard-native-owned-handles-exited',handles_dead,m.OWNED,'cleanup')
    if all_closed and handles_dead and not any('error' in x for x in m.CLEANUP):shutil.rmtree(m.RUNTIME)
    m.check('guard-native-temporary-runtime-removed',not m.RUNTIME.exists(),str(m.RUNTIME),'cleanup')
    report['hashesEnd']=m.hashes();m.check('guard-native-spec-and-candidates-unchanged',report['hashesBegin']==report['hashesEnd']==m.EXPECTED,report['hashesEnd'],'freeze')
    report.update(cases=m.CASES,commands=m.COMMANDS,cleanup=m.CLEANUP,ownedProcesses=m.OWNED,scenarioPorts={s.name:s.ports for s in m.SCENARIOS})
    m.dump(p/'g34-guard-native-evidence-v7.json',report)
failed=[x for x in m.CASES if not x['passed']]
print(json.dumps({'total':len(m.CASES),'passed':len(m.CASES)-len(failed),'failed':failed,'fatal':report.get('fatal'),'evidence':str(p/'g34-guard-native-evidence-v7.json')}),flush=True)
sys.exit(0 if not failed else 1)
