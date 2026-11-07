"""Fresh independent round-two candidate integration harness. Stdlib only."""
import collections, hashlib, http.client, json, os, pathlib, shutil, socket, subprocess, sys, threading, time, traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
ROOT=pathlib.Path(__file__).resolve().parent
BASE=ROOT.parent
SCR=ROOT/'scratch-router'
EVD=ROOT/'router-evidence.jsonl'
RECS=[]; PROCS=[]; SERVERS=[]; EVENTS=[]; LOCK=threading.Lock()
def digest(p): return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def record(case, ok, raw, command=None):
 r={'case':case,'pass':bool(ok),'at':time.time(),'command':command or f'python round2/router_harness.py --case {case} (internal integration step)','raw':raw}
 RECS.append(r)
 with EVD.open('a',encoding='utf-8') as f: f.write(json.dumps(r,ensure_ascii=False)+'\n')
 print(json.dumps({'case':case,'pass':bool(ok),'raw':raw},ensure_ascii=False),flush=True)
def choose_port():
 with socket.socket() as s:s.bind(('127.0.0.1',0)); p=s.getsockname()[1]
 assert p>=10000 and p not in (18791,8788,8789,8790,8765)
 return p
def wait_until(fn, seconds=5):
 end=time.monotonic()+seconds; last=None
 while time.monotonic()<end:
  try:
   last=fn()
   if last:return last
  except (OSError,ValueError,http.client.HTTPException):pass
  time.sleep(.04)
 raise TimeoutError(str(last))
def request(port,path,method='GET',behavior=None,body=b'{}'):
 conn=http.client.HTTPConnection('127.0.0.1',port,timeout=3)
 try:
  headers={'Authorization':'Bearer ROUND2-SYNTHETIC-MAIN','Content-Type':'application/json'}
  if behavior:headers['X-Round2-Behavior']=behavior
  conn.request(method,path,body if method=='POST' else None,headers)
  resp=conn.getresponse(); raw=resp.read()
  return {'status':resp.status,'body':raw.decode('utf-8',errors='replace'),'headers':dict(resp.getheaders())}
 finally:conn.close()
def status(port):return json.loads(request(port,'/_router/status')['body'])
def event(e):
 with LOCK:EVENTS.append(dict(at=time.monotonic(),**e))
class Handler(BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def log_message(self,*a):pass
 def emit(self,status_code,body,ctype='application/json'):
  b=body if isinstance(body,bytes) else json.dumps(body).encode()
  self.send_response(status_code);self.send_header('Content-Type',ctype);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b);self.wfile.flush()
 def do_GET(self):
  pool=self.server.pool
  if self.path=='/healthz':
   mode=self.server.health
   code=200;body={'service':'workbuddy2api','healthy':3}
   if mode=='wrong':body={'service':'random-stub','healthy':3}
   elif mode=='invalid_json':body=b'hello world'
   elif mode=='not_found':code=404;body={'service':'workbuddy2api','healthy':3}
   elif mode=='zero':body['healthy']=0
   elif mode=='negative':body['healthy']=-1
   elif mode=='fraction':body['healthy']=1.5
   elif mode=='string':body['healthy']='3'
   elif mode=='boolean':body['healthy']=True
   elif mode=='missing':del body['healthy']
   elif mode=='oversize':body={'service':'workbuddy2api','healthy':3,'pad':'z'*2000}
   elif mode=='timeout':time.sleep(.45)
   try:self.emit(code,body)
   except OSError:pass
   return
  event({'kind':'GET','pool':pool,'path':self.path})
  if self.path=='/v1/models':self.emit(200,{'data':[{'id':pool+'-model-b'},{'id':pool+'-model-a'}]});return
  if self.path in ('/panel/','/status'):self.emit(200,{'pool':pool,'route':self.path});return
  self.emit(404,{'error':'no fake route'})
 def do_POST(self):
  mode=self.headers.get('X-Round2-Behavior','ok');pool=self.server.pool
  size=int(self.headers.get('Content-Length',0))
  event({'kind':'POST_BEGIN','pool':pool,'path':self.path,'mode':mode,'x-router':self.headers.get('X-Wb-Router'),'authIsAuxSynthetic':self.headers.get('Authorization')=='Bearer ROUND2-SYNTHETIC-AUX','hasXApiKey':bool(self.headers.get('X-Api-Key'))})
  raw=self.rfile.read(size)
  event({'kind':'POST_BODY','pool':pool,'mode':mode,'declaredBytes':size,'actualBytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()})
  if len(raw)<size:event({'kind':'UPLOAD_CANCEL','pool':pool});return
  if mode=='reset':self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
  if mode=='partial':
   self.send_response(200);self.send_header('Content-Type','text/plain');self.send_header('Content-Length','100');self.end_headers();self.wfile.write(b'partial');self.wfile.flush();self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
  if mode in ('404','429'):self.emit(int(mode),{'error':mode,'pool':pool});return
  if mode in ('sse','cancel'):
   self.send_response(200);self.send_header('Content-Type','text/event-stream');self.send_header('Connection','close');self.end_headers();self.wfile.flush()
   parts=[b'data: {"word":"hel',b'lo","utf8":"',bytes([0xe6,0xb5]),bytes([0x8b]),b'"}\n\n',b'data: [DONE]\n\n']
   if mode=='cancel':parts=[b'data: tick\n\n']*80
   try:
    for i,chunk in enumerate(parts):
     self.wfile.write(chunk);self.wfile.flush();event({'kind':'SSE_WRITE','pool':pool,'mode':mode,'i':i,'hex':chunk.hex()});time.sleep(.12 if mode=='sse' else .06)
   except (OSError,ConnectionError):event({'kind':'SSE_CANCELLED','pool':pool,'mode':mode})
   self.close_connection=True;return
  self.emit(200,{'pool':pool,'path':self.path,'receivedSha256':hashlib.sha256(raw).hexdigest()})
def fake_server(pool):
 srv=ThreadingHTTPServer(('127.0.0.1',0),Handler);srv.daemon_threads=True;srv.pool=pool;srv.health='ok';SERVERS.append(srv)
 threading.Thread(target=srv.serve_forever,daemon=True).start();return srv
class Router:
 def __init__(self,name,main,aux,state='offline',paused=False,hold=100000000):
  self.dir=SCR/name;self.dir.mkdir(parents=True)
  shutil.copyfile(BASE/'candidate/wb-router.js',self.dir/'wb-router.js')
  shutil.copyfile(SCR/'netstat.exe',self.dir/'netstat.exe')
  (self.dir/'qq-mode.txt').write_text('offline')
  self.port=choose_port();self.out=(ROOT/(name+'-stdout.log')).open('w',encoding='utf-8')
  cfg={'listen':self.port,'main':{'host':'127.0.0.1','port':main.server_port,'key':'ROUND2-SYNTHETIC-MAIN'},'aux':{'host':'127.0.0.1','port':aux.server_port,'key':'ROUND2-SYNTHETIC-AUX'},'probeMs':80,'auxHealthMs':60,'healthTimeoutMs':150,'healthMaxBytes':1024,'healthStaleMs':400,'onlineHoldMs':hold,'offlineHoldMs':hold,'qqPort':62371,'mainWeight':5,'auxWeight':3,'requestTimeoutMs':1800,'auxPauseUntil':'2099-01-01T00:00:00.000Z' if paused else None}
  (self.dir/'router.config.json').write_text(json.dumps(cfg),encoding='utf-8')
  (self.dir/'router-state.json').write_text(json.dumps({'state':state,'changedAt':'2026-11-07T00:00:00Z','raw':None}),encoding='utf-8')
  env=os.environ.copy();env['PATH']=str(self.dir)+os.pathsep+env['PATH']
  self.cmd=[shutil.which('node'),str(self.dir/'wb-router.js')]
  self.proc=subprocess.Popen(self.cmd,cwd=self.dir,env=env,stdout=self.out,stderr=subprocess.STDOUT);PROCS.append(self.proc)
  wait_until(lambda:status(self.port))
  record('router_start_'+name,True,{'pid':self.proc.pid,'listen':self.port,'sourceSha256':digest(BASE/'candidate/wb-router.js'),'copySha256':digest(self.dir/'wb-router.js'),'transforms':[],'config':cfg},subprocess.list2cmdline(self.cmd))
 def stop(self):
  if self.proc.poll() is None:self.proc.terminate();self.proc.wait(5)
  self.out.close()
def snap_events(index):
 with LOCK:return list(EVENTS[index:])
def posts_since(index):return [e for e in snap_events(index) if e['kind']=='POST_BEGIN']
def wait_health(router,main,aux):
 return wait_until(lambda: (lambda s:s if s['main']['healthy']==main and s['aux']['healthy']==aux else None)(status(router.port)),4)
def suite():
 SCR.mkdir();
 csc=pathlib.Path('C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe')
 cp=subprocess.run([str(csc),'/nologo','/target:exe','/out:'+str(SCR/'netstat.exe'),str(ROOT/'FakeNetstat.cs')],capture_output=True,text=True)
 record('compile_netstat',cp.returncode==0,{'exit':cp.returncode,'stdout':cp.stdout,'stderr':cp.stderr},subprocess.list2cmdline(cp.args));assert cp.returncode==0
 main=fake_server('main');aux=fake_server('aux');r=Router('routing',main,aux)
 paths=['/v1/chat/completions','/v1/responses','/v1/images/generations','/v1/images/edits','/v1/embeddings','/v1/messages','/messages','/v1/search','/v1/fetch','/v2/chat/busy']
 idx=len(EVENTS);responses=[request(r.port,paths[i%len(paths)],'POST') for i in range(80)]
 counts=collections.Counter(e['pool'] for e in posts_since(idx));s=status(r.port)
 record('G5_offline_5_to_3',all(x['status']==200 for x in responses) and counts=={'main':50,'aux':30},{'requestCount':80,'counts':dict(counts),'routes':paths,'status':s,'responses':responses})
 for pool in (main,aux):
  for mode in ('wrong','invalid_json','not_found','zero','negative','fraction','string','boolean','missing','oversize','timeout'):
   pool.health=mode;expected_main=(pool is aux);expected_aux=(pool is main);s=wait_health(r,expected_main,expected_aux)
   idx=len(EVENTS);result=request(r.port,'/v1/chat/completions','POST');posts=posts_since(idx);healthy_pool='aux' if pool is main else 'main'
   record('G5_reject_'+pool.pool+'_'+mode,result['status']==200 and len(posts)==1 and posts[0]['pool']==healthy_pool,{'status':s,'response':result,'posts':posts})
  pool.health='ok';wait_health(r,True,True)
 main.health='wrong';aux.health='zero';s=wait_health(r,False,False);idx=len(EVENTS);out=request(r.port,'/v1/chat/completions','POST')
 record('G5_both_bad_503',out['status']==503 and not posts_since(idx),{'status':s,'response':out,'posts':posts_since(idx)})
 main.health='wrong';aux.health='ok';wait_health(r,False,True)
 for path in ('/healthz','/v1/models','/panel/','/status'):
  idx=len(EVENTS);out=request(r.port,path)
  record('G5_main_only_bad_'+path,out['status']==503 and not [e for e in snap_events(idx) if e['kind']=='GET'],{'response':out,'events':snap_events(idx)})
 main.health='ok';wait_health(r,True,True)
 for path in ('/v1/models','/panel/','/status'):
  idx=len(EVENTS);out=request(r.port,path);got=[e for e in snap_events(idx) if e['kind']=='GET']
  record('G5_main_only_good_'+path,out['status']==200 and len(got)==1 and got[0]['pool']=='main',{'response':out,'events':got})
 for state,paused in (('online',False),('offline',True)):
  rr=Router('policy-'+state+'-'+str(paused),main,aux,state,paused)
  idx=len(EVENTS);out=[request(rr.port,'/v1/chat/completions','POST') for _ in range(8)];sent=posts_since(idx)
  record('G5_policy_healthy_'+state+'_'+str(paused),all(x['status']==200 for x in out) and len(sent)==8 and all(x['pool']=='main' for x in sent),{'status':status(rr.port),'posts':sent,'responses':out})
  main.health='wrong';s=wait_health(rr,False,True);idx=len(EVENTS);out=request(rr.port,'/v1/chat/completions','POST')
  record('G5_policy_main_bad_'+state+'_'+str(paused),out['status']==503 and not posts_since(idx),{'status':s,'posts':posts_since(idx),'response':out})
  main.health='ok';wait_health(rr,True,True);rr.stop()
 # A fresh real-format netstat fixture checks the QQ transition itself independently.
 qq=Router('qq-transition',main,aux,'offline',False,120)
 (qq.dir/'qq-mode.txt').write_text('online')
 time.sleep(.6);s=status(qq.port)
 record('G5_QQ_online_transition',s['state']=='online' and s['qq']['raw'] is True,{'status':s,'netstatFixture':'  TCP    127.0.0.1:62371        127.0.0.1:62372        ESTABLISHED     12345','fakeCalls':(qq.dir/'netstat-calls.txt').read_text()})
 main.health='wrong';wait_health(qq,False,True);idx=len(EVENTS);out=request(qq.port,'/v1/chat/completions','POST');sent=posts_since(idx)
 record('K4_QQ_detected_no_aux',out['status']==503 and not sent,{'status':status(qq.port),'response':out,'posts':sent,'note':'QQ fixture reports an established connection; this is a real process execution of our independent fake netstat.exe'})
 main.health='ok';wait_health(r,True,True);qq.stop()
 for mode in ('404','429','reset','partial'):
  idx=len(EVENTS)
  try:out=request(r.port,'/v1/chat/completions','POST',mode,b'{"synthetic":"single-dispatch"}')
  except Exception as e:out={'errorType':type(e).__name__,'error':str(e)}
  time.sleep(.15);sent=posts_since(idx)
  good=len(sent)==1
  if mode in ('404','429'):good=good and out.get('status')==int(mode)
  record('G6_no_replay_'+mode,good,{'response':out,'posts':sent,'allEvents':snap_events(idx)})
 # SSE byte fidelity and arrival time, including a split UTF-8 code point.
 idx=len(EVENTS);conn=http.client.HTTPConnection('127.0.0.1',r.port,timeout=3);start=time.monotonic();conn.request('POST','/v1/responses',b'{}',{'X-Round2-Behavior':'sse'});resp=conn.getresponse();head_at=time.monotonic()-start;chunks=[]
 while True:
  b=resp.read1(4096)
  if not b:break
  chunks.append({'afterSeconds':time.monotonic()-start,'hex':b.hex()})
 conn.close();expected=b'data: {"word":"hello","utf8":"\xe6\xb5\x8b"}\n\ndata: [DONE]\n\n';actual=b''.join(bytes.fromhex(x['hex']) for x in chunks)
 record('G6_SSE',resp.status==200 and actual==expected and chunks[0]['afterSeconds']<.30 and chunks[-1]['afterSeconds']>.45 and len(chunks)>=4,{'status':resp.status,'headers':dict(resp.getheaders()),'headerAfterSeconds':head_at,'chunks':chunks,'expectedHex':expected.hex(),'actualHex':actual.hex(),'events':snap_events(idx)})
 idx=len(EVENTS);conn=http.client.HTTPConnection('127.0.0.1',r.port,timeout=3);conn.request('POST','/v1/responses',b'{}',{'X-Round2-Behavior':'cancel'});resp=conn.getresponse();first=resp.read1(100);cancel_at=time.monotonic();conn.close();resp.close()
 try:wait_until(lambda:any(x['kind']=='SSE_CANCELLED' for x in snap_events(idx)),2);cancelled=True
 except TimeoutError:cancelled=False
 record('G6_client_cancel',cancelled and len(posts_since(idx))==1,{'firstHex':first.hex(),'elapsedToObservedCancel':time.monotonic()-cancel_at,'events':snap_events(idx)})
 idx=len(EVENTS);sock=socket.create_connection(('127.0.0.1',r.port));sock.sendall(b'POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\nContent-Length: 50000\r\n\r\n'+b'x'*64);time.sleep(.08);sock.close()
 try:wait_until(lambda:any(x['kind']=='UPLOAD_CANCEL' for x in snap_events(idx)),2);cancelled=True
 except TimeoutError:cancelled=False
 record('G6_upload_cancel',cancelled and len(posts_since(idx))==1,{'events':snap_events(idx)})
 r.stop()
 record('native_processes_router',True,{'pids':[p.pid for p in PROCS],'ports':[srv.server_port for srv in SERVERS]})
def main():
 if EVD.exists() or SCR.exists():raise RuntimeError('refuse to overwrite earlier test artifacts')
 try:suite()
 except Exception as e:record('HARNESS_ERROR',False,{'type':type(e).__name__,'message':str(e),'traceback':traceback.format_exc()})
 finally:
  cleanup=[]
  for p in PROCS:
   if p.poll() is None:
    p.terminate()
    try:p.wait(5)
    except subprocess.TimeoutExpired:p.kill();p.wait(3)
   cleanup.append({'pid':p.pid,'exit':p.returncode,'running':p.poll() is None})
  for srv in SERVERS:srv.shutdown();srv.server_close()
  # Preserve source, raw evidence and logs; remove self-built runtime package only.
  if SCR.exists():
   for f in SCR.rglob('*'):
    if f.is_file() and f.name in ('wb-router.js','netstat.exe'):pass
   shutil.rmtree(SCR)
  record('cleanup_router',all(not x['running'] for x in cleanup),{'processes':cleanup,'scratchRemoved':not SCR.exists(),'portsClosed':[{'port':srv.server_port,'accepting':port_open(srv.server_port)} for srv in SERVERS]})
  (ROOT/'router-results.json').write_text(json.dumps(RECS,ensure_ascii=False,indent=2),encoding='utf-8')
  (ROOT/'router-mock-events.json').write_text(json.dumps(EVENTS,ensure_ascii=False,indent=2),encoding='utf-8')
  return 0 if all(r['pass'] for r in RECS) else 1
def port_open(p):
 try:
  with socket.create_connection(('127.0.0.1',p),timeout=.2):return True
 except OSError:return False
if __name__=='__main__':sys.exit(main())
