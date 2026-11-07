"""Independent round1 native startup/guard acceptance. All writes stay in round1.
Test adapters remap fixed service ports to exclusive high ports; the exact frozen
candidate cmd, guard and helper bytes run unchanged. No production POST or stop.
"""
from __future__ import annotations
import datetime as dt
import hashlib
import http.client
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parent
BASE = ROOT.parent
CANDIDATE = BASE / 'candidate'
PS = Path(os.environ['SystemRoot']) / 'System32/WindowsPowerShell/v1.0/powershell.exe'
CMD = Path(os.environ['SystemRoot']) / 'System32/cmd.exe'
CSC = Path(os.environ['SystemRoot']) / 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
NODE = Path(shutil.which('node') or '')
EXPECTED = {
    'acceptance-spec.md':'c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91',
    'acceptance-spec.lock':'50f75118dffa481cd7299f17c6e2981b7dcc96252261cc1543382428bf0fe88b',
    'candidate/start-silent.cmd':'10b4065059c4cc0b35a30d4a1875285bc505ac23066e5a9fc5e3ac40333604e7',
    'candidate/wb-service-check.ps1':'66091dea24cad91aa8b96c95de90857c22d3c0e98399b311d2ca72362f8ae512',
    'candidate/wb-gateway-guard.ps1':'5d7e6cb35a4a60f98ec72a6ba006690bbb93c40a2f97e34abc802331dd43d1c1',
    'candidate/wb-router.js':'2262451f13e4dcef160bb133d1fd17754aa56f0a4ace45c069ea0ebf59c7ce0f',
}
RUNTIME = ROOT / ('.runtime-g34-' + str(os.getpid()))
CASES, COMMANDS, CLEANUP, SCENARIOS, OWNED = [], [], [], [], []
REPORT = {'startedAt':dt.datetime.now().astimezone().isoformat(), 'tester':'independent-round1',
          'productionRequests':[], 'adapters':[
              'CMD powershell.exe PATH adapter preserves helper arguments and remaps only ports; hardcoded sanitizer call is recorded with simulated return codes, never executed on production paths.',
              'Full guard uses exact frozen guard plus exact helper imported through high-port definitions and WaitSeconds trace adapter. Native Get-NetTCPConnection, Get-CimInstance, HTTP, WScript starters remain real.'
          ]}

def hashes(): return {p:hashlib.sha256((BASE/p).read_bytes()).hexdigest() for p in EXPECTED}
def check(name, passed, detail=None, group='G4'):
    CASES.append({'name':name,'group':group,'passed':bool(passed),'detail':detail})
    print(('PASS ' if passed else 'FAIL ') + name, flush=True)
def dump(p, value): p.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
def read_jsonl(p):
    if not p.exists(): return []
    return [json.loads(x.lstrip('\ufeff')) for x in p.read_text(encoding='utf-8-sig').splitlines() if x.strip()]
def run(argv, *, cwd=None, env=None, timeout=60, label='command'):
    started=time.monotonic()
    completed=subprocess.run([str(x) for x in argv],cwd=cwd,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
    text=completed.stdout.decode('utf-8-sig',errors='replace')
    item={'label':label,'argv':[str(x) for x in argv],'cwd':str(cwd or Path.cwd()),'exitCode':completed.returncode,'elapsedMs':round((time.monotonic()-started)*1000),'output':text}
    COMMANDS.append(item)
    return completed.returncode,text

def ps_run(script, args=(), **kw):
    return run([PS,'-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',script,*args],**kw)
def allocate_port():
    with socket.socket() as s:
        if hasattr(socket,'SO_EXCLUSIVEADDRUSE'): s.setsockopt(socket.SOL_SOCKET,socket.SO_EXCLUSIVEADDRUSE,1)
        s.bind(('127.0.0.1',0)); port=s.getsockname()[1]
    if port in (8788,8789,8790,6199) or port<=1024: raise RuntimeError('unsafe test port')
    return port

def get(port,path,timeout=2):
    assert port not in (8788,8789,8790,6199)
    conn=http.client.HTTPConnection('127.0.0.1',port,timeout=timeout)
    try:
        conn.request('GET',path); response=conn.getresponse(); return response.status,response.read()
    finally: conn.close()
def port_closed(port):
    with socket.socket() as s:
        s.settimeout(.2); return s.connect_ex(('127.0.0.1',port)) != 0

def vbs_quote(s): return str(s).replace('"','""')
def make_vbs(p, executable, arguments):
    command=subprocess.list2cmdline([str(executable),*[str(x) for x in arguments]])
    lines = ['Option Explicit','Dim shell','Set shell = CreateObject("WScript.Shell")','shell.Run "'+vbs_quote(command)+'", 0, False','']
    p.write_bytes((chr(13)+chr(10)).join(lines).encode('utf-8'))

class Scenario:
    def __init__(self,name):
        self.name='v6-'+name; self.dir=RUNTIME/name; self.dir.mkdir()
        self.dirs={n:self.dir/('run-bot' if n=='aux' else 'run') for n in ('main','aux','router')}
        for d in set(self.dirs.values()): d.mkdir()
        (self.dir/'foreign').mkdir()
        self.ports={n:allocate_port() for n in ('main','aux','router')}
        self.ready=set(); self.handles={}; self.streams=[]
        self.tokens={n:uuid.uuid4().hex for n in self.ports}
        self.modes={n:self.dir/(n+'-http-mode.json') for n in self.ports}
        self.receipts={n:ROOT/('g34-'+self.name+'-'+n+'-receipts.jsonl') for n in self.ports}
        self.pidfiles={n:self.dir/(n+'.pid') for n in self.ports}
        for n in ('main','aux'):
            shutil.copy2(RUNTIME/'fixture.exe',self.dirs[n]/'wb2api.exe')
        shutil.copy2(RUNTIME/'fixture.exe',self.dir/'foreign/wb2api.exe')
        for f in ('start-silent.cmd','wb-service-check.ps1'):
            shutil.copy2(CANDIDATE/f,self.dir/'run'/f)
            assert hashlib.sha256((self.dir/'run'/f).read_bytes()).hexdigest()==EXPECTED['candidate/'+f]
        for dest in (self.dir/'run/wb-router.js',self.dir/'foreign/foreign-router.js'):
            shutil.copy2(ROOT/'g34-native-router-fixture.cjs',dest)
        for n in self.ports:
            cfg={'port':self.ports[n],'bodyFile':str(self.modes[n]),'receiptFile':str(self.receipts[n]),'stopToken':self.tokens[n],'pidFile':str(self.pidfiles[n])}
            if n=='router':
                dump(self.dir/'run/router-fixture.json',cfg); dump(self.dir/'foreign/router-fixture.json',cfg)
            else: dump(self.dirs[n]/'config.json',cfg)
            self.mode(n,'healthy')
        dump(self.dir/'port-map.json',self.ports)
        shutil.copy2(ROOT/'g34-guard-bridge.ps1',self.dir/'guard-bridge.ps1')
        shim=self.dir/'shim'; shim.mkdir(); shutil.copy2(RUNTIME/'fixture.exe',shim/'powershell.exe')
        dump(self.dir/'run/shim-control.json',{'calls':str(self.dir/'shim-calls.jsonl'),'sanitizeCode':0,'bridge':str(ROOT/'g34-cli-bridge.ps1'),'portMap':str(self.dir/'port-map.json'),'powershell':str(PS),'originalPath':os.environ.get('PATH','')})
        self.env=os.environ.copy(); self.env['PATH']=str(shim)+os.pathsep+self.env.get('PATH',''); self.env['TEMP']=str(self.dir); self.env['TMP']=str(self.dir)
        make_vbs(self.dir/'run/start-router.vbs',NODE,[self.dir/'run/wb-router.js'])
        make_vbs(self.dir/'run/start-silent.vbs',self.dir/'run/wb2api.exe',['-config',self.dir/'run/config.json'])
        make_vbs(self.dir/'run-bot/start-bot.vbs',self.dir/'run-bot/wb2api.exe',['-config',self.dir/'run-bot/config.json'])
        SCENARIOS.append(self)
    def mode(self,n,state):
        if n=='router':
            obj={'state':'offline','qq':{},'aux':{'target':'127.0.0.1:'+str(self.ports['aux']),'healthy':True,'paused':False},'main':{'target':'127.0.0.1:'+str(self.ports['main']),'healthy':True},'mainOk':True,'auxOk':True,'hold':{'onlineMs':0,'offlineMs':0},'weight':{'main':5,'aux':3},'counters':{'requests':0,'toMain':0,'toAux':0}}
        else: obj={'service':'workbuddy2api','healthy':7 if n=='main' else 1}
        mode={'status':200,'contentType':'application/json','body':json.dumps(obj)}
        if state=='unhealthy':
            if n=='router': mode['body']='{"state":"fake"}'
            else: mode['body']='{"service":"workbuddy2api","healthy":0}'
        dump(self.modes[n],mode)
    def launch(self,n,foreign=False):
        assert port_closed(self.ports[n])
        if self.pidfiles[n].exists(): self.pidfiles[n].unlink()
        if n=='router': argv=[NODE,self.dir/('foreign/foreign-router.js' if foreign else 'run/wb-router.js')]
        else: argv=[self.dir/('foreign/wb2api.exe' if foreign else ('run-bot/wb2api.exe' if n=='aux' else 'run/wb2api.exe')),'-config',self.dirs[n]/'config.json']
        output=ROOT/('g34-'+self.name+'-'+n+'-stdout.log')
        stream=output.open('xb'); self.streams.append(stream)
        p=subprocess.Popen([str(x) for x in argv],cwd=self.dirs[n],env={**os.environ,'TEMP':str(self.dir),'TMP':str(self.dir)},stdout=stream,stderr=subprocess.STDOUT)
        self.handles[n]=p; OWNED.append({'kind':'fixture','scenario':self.name,'name':n,'pid':p.pid,'argv':[str(x) for x in argv]})
        self.await_ready(n)
    def await_ready(self,n,timeout=28):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            p=self.handles.get(n)
            if p and p.poll() is not None: raise RuntimeError('owned fixture exited before readiness '+self.name+'/'+n+' code='+str(p.returncode))
            if self.pidfiles[n].exists():
                try:
                    status,body=get(self.ports[n], '/_router/status' if n=='router' else '/healthz')
                    if status==200:
                        self.ready.add(n); return
                except (OSError,http.client.HTTPException): pass
            time.sleep(.08)
        raise RuntimeError('fixture readiness deadline '+self.name+'/'+n)
    def launches(self,n): return [x for x in read_jsonl(self.receipts[n]) if x['kind']=='launch']
    def cmd(self,timeout=70):
        return run([CMD,'/d','/c',self.dir/'run/start-silent.cmd'],cwd=self.dir/'run',env=self.env,timeout=timeout,label='startup-'+self.name)
    def guard(self,no_restart=False,wait_seconds=2):
        args=['-PanelRoot',str(self.dir),'-HelperPath',str(self.dir/'guard-bridge.ps1'),'-LogPath',str(self.dir/'guard.log'),'-WatchScript',str(RUNTIME/'independent-watch.ps1'),'-WatchPidFile',str(RUNTIME/'watch.pid'),'-StartWaitSeconds',str(wait_seconds)]
        if no_restart: args.append('-NoRestart')
        rc,text=ps_run(CANDIDATE/'wb-gateway-guard.ps1',args,env={**os.environ,'TEMP':str(self.dir),'TMP':str(self.dir)},timeout=90,label='guard-'+self.name)
        (ROOT/('g34-'+self.name+'-guard-output.log')).write_text(text,encoding='utf-8')
        return rc,text
    def stop(self,n):
        if n not in self.ready:
            if self.pidfiles[n].exists() and not port_closed(self.ports[n]): self.ready.add(n)
            else: return
        assert self.pidfiles[n].exists() and self.launches(n),'owned process receipt required before teardown'
        expected_pid=int(self.pidfiles[n].read_text())
        assert any(x['pid']==expected_pid for x in self.launches(n))
        status,body=get(self.ports[n],'/__independent_stop/'+self.tokens[n])
        assert status==200 and json.loads(body)=={'stopped':True}
        deadline=time.monotonic()+6
        while time.monotonic()<deadline and not port_closed(self.ports[n]): time.sleep(.05)
        self.ready.discard(n)
        p=self.handles.get(n)
        if p: p.wait(timeout=6)
        CLEANUP.append({'scenario':self.name,'name':n,'pid':expected_pid,'method':'owned fixture GET self-stop','port':self.ports[n],'portClosed':port_closed(self.ports[n]),'handleExitCode':p.returncode if p else None})
    def cleanup(self):
        for n in self.ports: self.stop(n)
        for stream in self.streams: stream.close()
        for n,p in self.handles.items():
            if p.poll() is None: raise RuntimeError('owned process still alive '+str(p.pid))
        for n,p in self.ports.items():
            if not port_closed(p): raise RuntimeError('isolated port still listening '+str(p))
        # Preserve the shim and wait traces as necessary formal evidence.
        for source in ('shim-calls.jsonl','wait-calls.jsonl','guard.log'):
            p=self.dir/source
            if p.exists(): shutil.copy2(p,ROOT/('g34-'+self.name+'-'+source))
        shutil.rmtree(self.dir)


def test_startup():
    for name,state in [('healthy','healthy'),('main-foreign','foreign'),('main-unhealthy','unhealthy'),('router-foreign','router-foreign'),('sanitize-one','sanitize-one'),('sanitize-fail','sanitize-fail'),('hold-main-missing','hold-main-missing'),('router-missing-wscript','router-missing-wscript')]:
        s=Scenario('startup-'+name)
        try:
            if state!='hold-main-missing': s.launch('main',foreign=(state=='foreign'))
            if state=='unhealthy': s.mode('main','unhealthy')
            if state not in ('hold-main-missing','router-missing-wscript','foreign','unhealthy','sanitize-fail'): s.launch('router',foreign=(state=='router-foreign'))
            if state=='hold-main-missing': (s.dir/'run/.hold-restart').write_text('independent hold')
            if state.startswith('sanitize-'):
                p=s.dir/'run/shim-control.json'; c=json.loads(p.read_text()); c['sanitizeCode']=1 if state=='sanitize-one' else 2; dump(p,c)
            before={n:len(s.launches(n)) for n in s.ports}
            rc,text=s.cmd()
            if state=='router-missing-wscript': s.await_ready('router')
            after={n:len(s.launches(n)) for n in s.ports}
            expected=20 if state in ('foreign','router-foreign') else 30 if state=='unhealthy' else 40 if state=='sanitize-fail' else 10 if state=='hold-main-missing' else 0
            check('startup-'+name+'-exit',rc==expected,{'expected':expected,'actual':rc,'output':text},'G3')
            expected_after={**before}
            if state=='router-missing-wscript': expected_after['router']=1
            check('startup-'+name+'-launch-counts',after==expected_after,{'before':before,'after':after},'G3')
            if state in ('foreign','router-foreign'): check('startup-'+name+'-explicit-conflict','CONFLICT' in text,{'output':text},'G3')
            if state=='unhealthy': check('startup-main-unhealthy-explicit','state=unhealthy' in text and '30' in text,{'output':text},'G3')
            calls=read_jsonl(s.dir/'shim-calls.jsonl')
            check('startup-'+name+'-sanitizer-original-call-recorded',len(calls)>0 and any('wb-config-sanitize.ps1' in arg for arg in calls[0]['argv']) and '-Dir' in calls[0]['argv'] and '-LogPath' in calls[0]['argv'],calls,'G3')
            if state=='router-missing-wscript':
                wait=[c for c in calls if '-WaitSeconds' in c['argv']]
                check('startup-router-missing-real-wscript-and-wait20',len(wait)==1 and wait[0]['argv'][wait[0]['argv'].index('-WaitSeconds')+1]=='20' and len(s.launches('main'))==1,wait,'G3')
        finally: s.cleanup()
    s=Scenario('startup-main-missing')
    cmd_handle=None
    try:
        s.launch('router')
        stream=(ROOT/'g34-startup-main-missing-cmd-output-v5.log').open('xb'); s.streams.append(stream)
        argv=[str(CMD),'/d','/c',str(s.dir/'run/start-silent.cmd')]
        cmd_handle=subprocess.Popen(argv,cwd=s.dir/'run',env=s.env,stdout=stream,stderr=subprocess.STDOUT)
        OWNED.append({'kind':'startup-cmd','pid':cmd_handle.pid,'argv':argv})
        s.await_ready('main',timeout=35)
        check('startup-missing-main-launches-exact-owned-wb-config',len(s.launches('main'))==1 and len(s.launches('router'))==1,s.launches('main'),'G3')
        s.stop('main'); cmd_handle.wait(timeout=12)
        check('startup-missing-main-preserves-exe-exit',cmd_handle.returncode==0,{'exitCode':cmd_handle.returncode},'G3')
        check('startup-missing-main-panel-log-created',(s.dir/'run/panel.log').is_file(),None,'G3')
        COMMANDS.append({'label':'startup-main-missing','argv':argv,'exitCode':cmd_handle.returncode,'outputFile':str(ROOT/'g34-startup-main-missing-cmd-output-v5.log')})
    finally:
        s.cleanup()
        if cmd_handle and cmd_handle.poll() is None: raise RuntimeError('owned cmd did not exit')


def test_guard(only_pending=False):
    for label,target,state in [('healthy-twice',None,'healthy'),*[(n+'-foreign',n,'foreign') for n in ('main','aux','router')],*[(n+'-unhealthy',n,'unhealthy') for n in ('main','aux','router')],*[(n+'-missing-recover',n,'missing') for n in ('main','aux','router')],('NoRestart-all-missing',None,'NoRestart'),('hold-all-missing',None,'hold')]:
        if only_pending and state not in ('missing','NoRestart','hold'): continue
        if only_pending == 'hold-only' and state == 'missing': continue
        s=Scenario('guard-'+label)
        try:
            for n in s.ports:
                if state in ('NoRestart','hold') or (n==target and state=='missing'): continue
                s.launch(n,foreign=(n==target and state=='foreign'))
                if n==target and state=='unhealthy': s.mode(n,'unhealthy')
            if state=='hold':
                for d in set(s.dirs.values()): (d/'.hold-restart').write_text('independent hold')
            before={n:len(s.launches(n)) for n in s.ports}
            rc,text=s.guard(no_restart=state=='NoRestart',wait_seconds=2)
            if state=='missing': s.await_ready(target)
            expected=20 if state=='foreign' else 30 if state=='unhealthy' else 10 if state in ('NoRestart','hold') else 0
            check('guard-native-'+label+'-exit',rc==expected,{'expected':expected,'actual':rc,'output':text})
            after={n:len(s.launches(n)) for n in s.ports}; expected_after={**before}
            if state=='missing': expected_after[target]=1
            check('guard-native-'+label+'-launch-counts',after==expected_after,{'before':before,'after':after})
            if state=='foreign': check('guard-native-'+label+'-explicit-conflict','CONFLICT' in text and 'foreign' in text,{'output':text})
            if state=='unhealthy': check('guard-native-'+label+'-report-unhealthy','WARN UNHEALTHY' in text and 'unhealthy' in text,{'output':text})
            if state=='missing':
                waits=read_jsonl(s.dir/'wait-calls.jsonl')
                check('guard-native-'+label+'-WaitSeconds-survives-full-dot-source',len(waits)==1 and waits[0]['Name']==target and waits[0]['Seconds']==2,waits)
                check('guard-native-'+label+'-recovery-healthy','recovered' in text and 'HEALTHY after-recovery' in text,{'output':text})
            if state=='NoRestart': check('guard-native-NoRestart-all-three-explicit',text.count('NO_RESTART')==3 and not (s.dir/'wait-calls.jsonl').exists(),{'output':text})
            if state=='hold': check('guard-native-hold-all-three-explicit',text.count('HOLD ')==3 and not (s.dir/'wait-calls.jsonl').exists(),{'output':text})
            if state=='healthy':
                rc2,text2=s.guard(); counts={n:len(s.launches(n)) for n in s.ports}
                check('guard-native-healthy-repeat-idempotent',rc2==0 and counts==before,{'exitCode':rc2,'launches':counts,'output':text2})
        finally: s.cleanup()


def main():
    if RUNTIME.exists(): raise RuntimeError('exclusive scratch directory exists')
    REPORT['hashesBegin']=hashes(); check('G34-begin-freeze-hashes',REPORT['hashesBegin']==EXPECTED,REPORT['hashesBegin'],'freeze')
    RUNTIME.mkdir(); build_env={**os.environ,'TEMP':str(RUNTIME),'TMP':str(RUNTIME)}
    watch=None; watch_stream=None
    try:
        rc,out=run([CSC,'/nologo','/target:exe','/r:System.Web.Extensions.dll','/out:'+str(RUNTIME/'fixture.exe'),ROOT/'IndependentWbFixture.cs'],env=build_env,timeout=30,label='compile-independent-native-fixture')
        check('native-fixture-compile',rc==0,{'exitCode':rc,'output':out},'harness')
        if rc!=0: raise RuntimeError('native fixture compile failed')
        mock_scratch=RUNTIME/'library-cases'; mock_scratch.mkdir()
        rc,out=ps_run(ROOT/'g34-library-independent.ps1',['-CandidateDir',str(CANDIDATE),'-Scratch',str(mock_scratch),'-Evidence',str(ROOT/'g34-library-evidence-v5.json')],env=build_env,timeout=45,label='independent-library-matrix')
        check('library-native-WinPS51-run',rc==0,{'exitCode':rc,'output':out},'harness')
        if (ROOT/'g34-library-evidence-v5.json').exists(): REPORT['library']=json.loads((ROOT/'g34-library-evidence-v5.json').read_text(encoding='utf-8-sig'))
        if rc!=0: raise RuntimeError('independent library tests failed')
        watch_script=RUNTIME/'independent-watch.ps1'
        watch_script.write_text('$ErrorActionPreference="Stop"\n[IO.File]::WriteAllText((Join-Path $PSScriptRoot "watch.pid"),[string]$PID)\n$deadline=[DateTime]::UtcNow.AddMinutes(8)\nwhile(-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot "watch.stop")) -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 100}\n',encoding='utf-8')
        watch_stream=(ROOT/'g34-watch-stdout-v5.log').open('xb')
        watch_argv=[str(PS),'-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',str(watch_script)]
        watch=subprocess.Popen(watch_argv,env=build_env,stdout=watch_stream,stderr=subprocess.STDOUT)
        OWNED.append({'kind':'watch-fixture','pid':watch.pid,'argv':watch_argv})
        deadline=time.monotonic()+10
        while not (RUNTIME/'watch.pid').exists() and time.monotonic()<deadline: time.sleep(.05)
        if not (RUNTIME/'watch.pid').exists(): raise RuntimeError('owned watcher not ready')
        test_startup(); test_guard()
    except Exception as exc:
        REPORT['fatal']={'type':type(exc).__name__,'message':str(exc)}
        check('harness-completed',False,REPORT['fatal'],'harness')
    finally:
        for scenario in SCENARIOS:
            if scenario.dir.exists():
                try: scenario.cleanup()
                except Exception as exc: CLEANUP.append({'scenario':scenario.name,'error':str(exc)})
        if watch is not None:
            (RUNTIME/'watch.stop').write_text('owned test watcher stop',encoding='utf-8')
            try: watch.wait(timeout=10)
            except subprocess.TimeoutExpired: REPORT['watchDidNotStop']=True
            CLEANUP.append({'name':'independent-watch','pid':watch.pid,'method':'owned stop file','exitCode':watch.returncode})
        if watch_stream: watch_stream.close()
        REPORT['hashesEnd']=hashes(); check('G34-end-freeze-hashes',REPORT['hashesEnd']==EXPECTED,REPORT['hashesEnd'],'freeze')
        all_closed=all(port_closed(port) for s in SCENARIOS for port in s.ports.values())
        handles_dead=all(p.poll() is not None for s in SCENARIOS for p in s.handles.values()) and (watch is None or watch.poll() is not None)
        check('G34-all-isolated-fixture-ports-closed',all_closed,{'ports':[p for s in SCENARIOS for p in s.ports.values()]},'cleanup')
        check('G34-owned-process-handles-exited',handles_dead,OWNED,'cleanup')
        if all_closed and handles_dead and not any('error' in row for row in CLEANUP):
            shutil.rmtree(RUNTIME)
        check('G34-temporary-runtime-removed',not RUNTIME.exists(),str(RUNTIME),'cleanup')
        REPORT.update({'endedAt':dt.datetime.now().astimezone().isoformat(),'cases':CASES,'commands':COMMANDS,'cleanup':CLEANUP,'ownedProcesses':OWNED,'scenarioPorts':{s.name:s.ports for s in SCENARIOS}})
        dump(ROOT/'g34-evidence-v5.json',REPORT)
    combined=CASES+REPORT.get('library',{}).get('cases',[])
    failed=[c for c in combined if not c['passed']]
    print(json.dumps({'total':len(combined),'passed':len(combined)-len(failed),'failed':len(failed),'failedNames':[c['name'] for c in failed],'fatal':REPORT.get('fatal'),'evidence':str(ROOT/'g34-evidence-v5.json')},ensure_ascii=False),flush=True)
    return 0 if not failed else 1

if __name__=='__main__': sys.exit(main())
