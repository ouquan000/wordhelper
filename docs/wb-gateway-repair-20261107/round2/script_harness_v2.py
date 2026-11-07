"""Independent OS-level startup and guard tests, own high ports/fake executables."""
import hashlib,http.client,json,os,pathlib,re,shutil,socket,subprocess,sys,threading,time,traceback
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
ROOT=pathlib.Path(__file__).resolve().parent;BASE=ROOT.parent;SCR=ROOT/'scratch-scripts-v2';PANEL=SCR/'panel';RUN=PANEL/'run';AUX=PANEL/'run-bot';EVD=ROOT/'script-v2-evidence.jsonl';RECS=[];PROCS=[];PORTS=[];HANDLES=[];FOREIGN=None
PS=shutil.which('powershell.exe');NODE=shutil.which('node')
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def rec(name,passed,raw,cmd=None):
 r={'case':name,'pass':bool(passed),'command':cmd or 'python round2/script_harness.py (case '+name+')','raw':raw};RECS.append(r)
 with EVD.open('a',encoding='utf-8') as f:f.write(json.dumps(r,ensure_ascii=False)+'\n')
 print(json.dumps({'case':name,'pass':bool(passed),'detail':raw if not isinstance(raw,dict) else {k:v for k,v in raw.items() if k in ('exit','stdout','stderr','state','note')}}),flush=True)
def invoke(args,timeout=75):
 cp=subprocess.run(args,capture_output=True,timeout=timeout)
 return {'exit':cp.returncode,'stdout':cp.stdout.decode('utf-8',errors='replace'),'stderr':cp.stderr.decode('utf-8',errors='replace'),'command':subprocess.list2cmdline(args)}
def port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));p=s.getsockname()[1]
 assert p>=10000 and p not in (18791,8765,8788,8789,8790);PORTS.append(p);return p
def open_port(p):
 try:
  with socket.create_connection(('127.0.0.1',p),.15):return True
 except OSError:return False
def wait_port(p):
 end=time.monotonic()+5
 while time.monotonic()<end:
  if open_port(p):return
  time.sleep(.04)
 raise TimeoutError('listener '+str(p))
def start(exe,cwd):
 out=(ROOT/(cwd.name+'-fake-output.log')).open('a',encoding='utf-8');HANDLES.append(out)
 p=subprocess.Popen([str(exe),'-config','config.json'],cwd=cwd,stdout=out,stderr=subprocess.STDOUT);PROCS.append(p);return p
def stop(p):
 if p.poll() is None:p.terminate();p.wait(5)
def psargs(script,*args):return [PS,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(script),*map(str,args)]
def health(p):
 c=http.client.HTTPConnection('127.0.0.1',p,timeout=2);c.request('GET','/healthz');r=c.getresponse();b=r.read();c.close();return {'status':r.status,'body':b.decode()}
def starts(d):
 p=d/'starts.txt';return p.read_text().splitlines() if p.exists() else []
class ForeignHandler(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def do_GET(self):
  b=b'{"service":"workbuddy2api","healthy":3}';self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
def suite():
 global FOREIGN
 SCR.mkdir();RUN.mkdir(parents=True);AUX.mkdir()
 # Native fake package is built afresh; no binaries or tests imported from another round.
 ports={'main':port(),'aux':port(),'router':port()}
 csc='C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe'
 cmd=[csc,'/nologo','/target:exe','/out:'+str(RUN/'wb2api.exe'),str(ROOT/'FakeWB.cs')];out=invoke(cmd);rec('build_fake_WB',out['exit']==0,out,out['command']);assert out['exit']==0
 shutil.copyfile(RUN/'wb2api.exe',AUX/'wb2api.exe')
 for name,d in (('main',RUN),('aux',AUX)):
  (d/'port.txt').write_text(str(ports[name]));(d/'mode.txt').write_text('healthy');(d/'config.json').write_text('{"round2":"synthetic-only"}',encoding='utf-8')
 helper=BASE/'candidate/wb-service-check.ps1';text=helper.read_text(encoding='utf-8');transforms=[]
 for old,name in ((8790,'main'),(8789,'aux'),(8788,'router')):
  pattern=r'(?<!\d)'+str(old)+r'(?!\d)';count=len(re.findall(pattern,text));text=re.sub(pattern,str(ports[name]),text);transforms.append({'kind':'isolated-port-literal','from':old,'to':ports[name],'occurrences':count})
 (RUN/'wb-service-check.ps1').write_text(text,encoding='utf-8')
 rec('isolation_helper_transform',True,{'source':str(helper),'sourceSha256':sha(helper),'copySha256':sha(RUN/'wb-service-check.ps1'),'transforms':transforms})
 sanitizer=SCR/'sanitize-recorder.ps1';sanitizer.write_text("param([string]$Dir,[string]$LogPath,[switch]$Quiet)\n[IO.File]::AppendAllText((Join-Path $Dir 'sanitizer-called.txt'), 'round2 sanitation recorder'+[Environment]::NewLine)\nexit 0\n",encoding='utf-8')
 startsrc=BASE/'candidate/start-silent.cmd';starttxt=startsrc.read_text(encoding='utf-8')
 starttxt,n1=re.subn(r'(?<=-File ")[^"]+wb-config-sanitize\.ps1',lambda m:str(sanitizer),starttxt,count=1)
 starttxt,n2=re.subn(r'(?<=-LogPath ")[^"]+',lambda m:str(SCR/'sanitize.log'),starttxt,count=1)
 assert n1==n2==1
 (RUN/'start-silent.cmd').write_bytes(starttxt.replace('\r\n','\n').replace('\n','\r\n').encode('utf-8'))
 rec('isolation_launcher_transform',True,{'sourceSha256':sha(startsrc),'copySha256':sha(RUN/'start-silent.cmd'),'transforms':[{'kind':'production-sanitizer-path','to':str(sanitizer),'occurrences':n1},{'kind':'production-log-path','to':str(SCR/'sanitize.log'),'occurrences':n2},{'kind':'cmd CRLF normalization'}],'note':'sanitation invocation is recorded; production sanitizer implementation is not under test'})
 shutil.copyfile(BASE/'candidate/wb-router.js',RUN/'wb-router.js')
 cfg={'listen':ports['router'],'main':{'port':ports['main'],'key':'ROUND2-SYNTHETIC-MAIN'},'aux':{'port':ports['aux'],'key':'ROUND2-SYNTHETIC-AUX'},'auxHealthMs':200,'healthStaleMs':1000,'probeMs':60000}
 (RUN/'router.config.json').write_text(json.dumps(cfg),encoding='utf-8')
 main=start(RUN/'wb2api.exe',RUN);aux=start(AUX/'wb2api.exe',AUX);wait_port(ports['main']);wait_port(ports['aux'])
 out=(ROOT/'scripts-router-output.log').open('w',encoding='utf-8');HANDLES.append(out);router=subprocess.Popen([NODE,str(RUN/'wb-router.js')],cwd=RUN,stdout=out,stderr=subprocess.STDOUT);PROCS.append(router);wait_port(ports['router'])
 rec('native_fake_identity',True,{'mainPid':main.pid,'auxPid':aux.pid,'routerPid':router.pid,'ports':ports,'fakeExeHash':sha(RUN/'wb2api.exe'),'mainHealthRaw':health(ports['main']),'auxHealthRaw':health(ports['aux'])})
 for name in ('main','aux','router'):
  args=psargs(RUN/'wb-service-check.ps1','-Service',name,'-PanelRoot',PANEL);res=invoke(args);rec('G3_native_helper_healthy_'+name,res['exit']==0,res,res['command'])
 # Execute the real CMD control flow after only path redirection.
 cmd=[shutil.which('cmd.exe'),'/d','/s','/c','"'+str(RUN/'start-silent.cmd')+'"']
 before=starts(RUN);res=invoke(cmd);after=starts(RUN)
 rec('G3_native_launcher_idempotent',res['exit']==0 and before==after and main.poll() is None,dict(res,startsBefore=before,startsAfter=after,mainPid=main.pid,mainStillRunning=main.poll() is None),res['command'])
 (RUN/'mode.txt').write_text('zero');before=starts(RUN);res=invoke(cmd);after=starts(RUN)
 rec('G3_native_launcher_correct_unhealthy',res['exit']==30 and before==after and main.poll() is None,dict(res,startsBefore=before,startsAfter=after,mainStillRunning=main.poll() is None),res['command']);(RUN/'mode.txt').write_text('healthy')
 # Native guard CLI is restricted with NoRestart; only self-built configs may be sanitized.
 guardcmd=psargs(BASE/'candidate/wb-gateway-guard.ps1','-NoRestart','-PanelRoot',PANEL,'-HelperPath',RUN/'wb-service-check.ps1','-LogPath',SCR/'guard.log','-WatchScript',SCR/'nonexistent-own-watcher.ps1','-WatchPidFile',SCR/'own-watcher.pid','-StartWaitSeconds',0)
 res=invoke(guardcmd,75);rec('G4_native_guard_healthy',all(re.search(r'\b'+n+r'\s+'+str(ports[n])+r'\s+healthy',res['stdout']) for n in ('main','aux','router')),res,res['command'])
 for name,d in (('main',RUN),('aux',AUX)):
  (d/'mode.txt').write_text('zero');res=invoke(guardcmd,75)
  rec('G4_native_guard_unhealthy_'+name,re.search(r'\b'+name+r'\s+'+str(ports[name])+r'\s+unhealthy',res['stdout']) is not None and main.poll() is None and aux.poll() is None,dict(res,mainStillRunning=main.poll() is None,auxStillRunning=aux.poll() is None),res['command']);(d/'mode.txt').write_text('healthy')
 stop(main);FOREIGN=ThreadingHTTPServer(('127.0.0.1',ports['main']),ForeignHandler);threading.Thread(target=FOREIGN.serve_forever,daemon=True).start()
 before=starts(RUN);res=invoke(cmd);after=starts(RUN)
 rec('G3_native_launcher_foreign',res['exit']==20 and before==after and open_port(ports['main']),dict(res,startsBefore=before,startsAfter=after,foreignStillListening=open_port(ports['main']),foreignHealthRaw=health(ports['main'])),res['command'])
 res=invoke(guardcmd,75);rec('G4_native_guard_foreign',re.search(r'\bmain\s+'+str(ports['main'])+r'\s+foreign',res['stdout']) is not None and open_port(ports['main']),dict(res,foreignStillListening=open_port(ports['main'])),res['command'])
 FOREIGN.shutdown();FOREIGN.server_close();FOREIGN=None
 (RUN/'.hold-restart').write_text('round2 intentional test hold')
 res=invoke(cmd);rec('G3_native_launcher_missing_held',res['exit']==10 and not open_port(ports['main']),res,res['command'])
 res=invoke(guardcmd,75);rec('G4_native_guard_missing_NoRestart',re.search(r'\bmain\s+'+str(ports['main'])+r'\s+missing',res['stdout']) is not None and 'no-restart' in res['stdout'] and not open_port(ports['main']),res,res['command'])
 # Independent function-level matrix is reported separately from the native CLI result.
 lib=invoke(psargs(ROOT/'script_library.ps1','-R2Root',SCR,'-R2Candidate',BASE/'candidate'),30)
 rec('library_harness_exit',lib['exit']==0,lib,lib['command'])
 for line in lib['stdout'].splitlines():
  if line.startswith('{'):
   try:r=json.loads(line);rec(r['case'],r['pass'],r['raw'],lib['command']+' (internal case '+r['case']+')')
   except Exception as e:rec('LIBRARY_RESULT_PARSE_ERROR',False,{'line':line,'error':str(e)})
 # Preserve own raw runtime logs before the disposable package is removed.
 logs={str(p.relative_to(SCR)):p.read_text(encoding='utf-8-sig',errors='replace') for p in SCR.rglob('*') if p.is_file() and p.name in ('guard.log','starts.txt','requests.txt','sanitizer-called.txt')}
 rec('native_runtime_logs',True,logs)
def main():
 global FOREIGN
 if EVD.exists() or SCR.exists():raise RuntimeError('refuse to overwrite existing independent artifacts')
 try:suite()
 except Exception as e:rec('HARNESS_ERROR',False,{'type':type(e).__name__,'message':str(e),'traceback':traceback.format_exc()})
 finally:
  if FOREIGN:FOREIGN.shutdown();FOREIGN.server_close()
  cleanup=[]
  for p in PROCS:
   stop(p);cleanup.append({'pid':p.pid,'exit':p.returncode,'running':p.poll() is None})
  for h in HANDLES:h.close()
  if SCR.exists():shutil.rmtree(SCR)
  rec('cleanup_scripts',all(not p['running'] for p in cleanup) and all(not open_port(p) for p in PORTS),{'processes':cleanup,'ports':[{'port':p,'accepting':open_port(p)} for p in PORTS],'scratchRemoved':not SCR.exists()})
  (ROOT/'script-v2-results.json').write_text(json.dumps(RECS,ensure_ascii=False,indent=2),encoding='utf-8')
  return 0 if all(r['pass'] for r in RECS) else 1
if __name__=='__main__':sys.exit(main())
