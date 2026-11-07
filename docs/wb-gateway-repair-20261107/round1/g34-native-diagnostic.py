"""Focused independent diagnosis of real helper HTTP/owner classification."""
import importlib.util, json, os, shutil, sys
sys.dont_write_bytecode=True
from pathlib import Path
p=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('g34_diagnostic_harness',p/'g34-independent.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
s=None
try:
    m.RUNTIME.mkdir()
    env={**os.environ,'TEMP':str(m.RUNTIME),'TMP':str(m.RUNTIME)}
    rc,text=m.run([m.CSC,'/nologo','/target:exe','/r:System.Web.Extensions.dll','/out:'+str(m.RUNTIME/'fixture.exe'),m.ROOT/'IndependentWbFixture.cs'],env=env,label='diagnostic-compile')
    if rc: raise RuntimeError(text)
    s=m.Scenario('native-http-diagnostic')
    s.launch('main');s.launch('router')
    for name in ('main','router'):
        rc,text=m.ps_run(m.ROOT/'g34-cli-bridge.ps1',['-IndependentTargetHelper',str(s.dir/'run/wb-service-check.ps1'),'-IndependentPortMap',str(s.dir/'port-map.json'),'-Service',name,'-PanelRoot',str(s.dir)],env=env,label='native-direct-helper-'+name)
        print(json.dumps({'name':name,'rc':rc,'output':text}),flush=True)
    rc,text=s.cmd();print(json.dumps({'name':'native-cmd','rc':rc,'output':text}),flush=True)
finally:
    if s:s.cleanup()
    if m.RUNTIME.exists():shutil.rmtree(m.RUNTIME)
    m.dump(m.ROOT/'g34-native-diagnostic-evidence-v5.json',{'commands':m.COMMANDS,'cleanup':m.CLEANUP,'hashes':m.hashes()})
