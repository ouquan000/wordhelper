"""Bounded native VBS fixture-launch diagnosis, owns every file/process below its runtime."""
import importlib.util,json,os,shutil,subprocess,sys
from pathlib import Path
sys.dont_write_bytecode=True
p=Path(__file__).resolve().parent
sp=importlib.util.spec_from_file_location('vbs_launch_harness',p/'g34-independent.py');m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m)
report={'hashesBegin':m.hashes()};s=None
try:
    m.RUNTIME.mkdir();env={**os.environ,'TEMP':str(m.RUNTIME),'TMP':str(m.RUNTIME)}
    code,text=m.run([m.CSC,'/nologo','/target:exe','/r:System.Web.Extensions.dll','/out:'+str(m.RUNTIME/'fixture.exe'),p/'IndependentWbFixture.cs'],env=env)
    if code:raise RuntimeError(text)
    s=m.Scenario('vbs-launch-diagnostic')
    vbs=s.dir/'run/start-silent.vbs';report['vbsSource']=vbs.read_bytes().decode('utf-8');report['vbsHex']=vbs.read_bytes().hex()
    argv=[str(m.PS.parent.parent/'cscript.exe')]
    argv=[str(Path(os.environ['SystemRoot'])/'System32/cscript.exe'),'//nologo',str(vbs)]
    code,text=m.run(argv,cwd=s.dir/'run',env=env,timeout=8,label='vbs-launch-with-console-diagnostics')
    report.update(exitCode=code,output=text)
    if code==0:s.await_ready('main',timeout=5)
except Exception as e:report['error']={'type':type(e).__name__,'message':str(e)}
finally:
    if s:s.cleanup()
    if m.RUNTIME.exists():shutil.rmtree(m.RUNTIME)
    report.update(commands=m.COMMANDS,cleanup=m.CLEANUP,hashesEnd=m.hashes(),scratchRemoved=not m.RUNTIME.exists())
    m.dump(p/'g34-vbs-launch-diagnostic-evidence.json',report)
print(json.dumps(report,ensure_ascii=True))
