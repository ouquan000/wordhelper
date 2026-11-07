import hashlib,http.client,json,pathlib,shutil,socket,subprocess,sys,time,traceback
ROOT=pathlib.Path(__file__).resolve().parent;BASE=ROOT.parent;SCR=ROOT/'scratch-g1';RECS=[];PROCS=[];HANDLES=[]
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def rec(name,passed,raw,cmd):
 r={'case':name,'pass':bool(passed),'command':cmd,'raw':raw};RECS.append(r);print(json.dumps({'case':name,'pass':bool(passed),'raw':raw}),flush=True)
def free(p):
 try:
  with socket.socket() as s:s.bind(('127.0.0.1',p))
  return True
 except OSError:return False
def req(p,path):
 c=http.client.HTTPConnection('127.0.0.1',p,timeout=2);c.request('GET',path);r=c.getresponse();b=r.read();c.close();return {'method':'GET','port':p,'path':path,'status':r.status,'body':b.decode('utf-8')}
def start(args,name,port):
 h=(ROOT/(name+'-output.log')).open('w',encoding='utf-8');HANDLES.append(h);p=subprocess.Popen(args,cwd=SCR,stdout=h,stderr=subprocess.STDOUT);PROCS.append(p)
 end=time.monotonic()+4
 while time.monotonic()<end:
  if p.poll() is not None:raise RuntimeError('own stub exited '+str(p.returncode))
  try:return p,req(port,'/rewrite/status')
  except OSError:time.sleep(.04)
 raise TimeoutError('own stub did not listen')
def stop(p):
 if p.poll() is None:p.terminate();p.wait(5)
def suite():
 SCR.mkdir()
 source=pathlib.Path('C:/temp/stub-rewrite.py');dest=SCR/'stub-rewrite.py';shutil.copyfile(source,dest)
 ids={str(p):sha(p) for p in [BASE/'acceptance-spec.md',BASE/'acceptance-spec.lock',BASE/'artifact-manifest.json',source,*[BASE/'candidate'/n for n in ('wb-router.js','start-silent.cmd','wb-service-check.ps1','wb-gateway-guard.ps1')]]}
 rec('artifact_identity_start',True,ids,'python round2/g1_harness.py (sha256 before tests)')
 rec('G1_stub_exact_copy',sha(source)==sha(dest),{'sourceSha256':sha(source),'copySha256':sha(dest),'transform':[]},'shutil.copyfile approved stub into round2/scratch-g1')
 for port in (8788,8789,8790):
  cmd=[sys.executable,str(dest),'--port',str(port)];cp=subprocess.run(cmd,cwd=SCR,capture_output=True,timeout=4)
  raw={'exit':cp.returncode,'stdout':cp.stdout.decode('utf-8',errors='replace'),'stderr':cp.stderr.decode('utf-8',errors='replace')}
  rec('G1_reserved_'+str(port),cp.returncode!=0 and 'reserved WB gateway ports' in raw['stderr'],raw,subprocess.list2cmdline(cmd))
 if not free(18791):rec('G1_default_port',False,{'state':'NOT_TESTED','reason':'default18791 already bound; did not disturb owner'},'socket bind-only availability check 18791')
 else:
  cmd=[sys.executable,str(dest)];p,status=start(cmd,'g1-default',18791)
  raw={'pid':p.pid,'port':18791,'status':status,'models':req(18791,'/models'),'results':req(18791,'/rewrite/results')}
  rec('G1_default_port',status['status']==200 and json.loads(status['body'])=={'queued':0,'done':0,'failed':0},raw,subprocess.list2cmdline(cmd)+'; GET /rewrite/status /models /rewrite/results');stop(p)
 with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
 assert port>=10000 and free(port)
 cmd=[sys.executable,str(dest),'--host','127.0.0.1','--port',str(port)];p,status=start(cmd,'g1-explicit',port)
 rec('G1_explicit_port',status['status']==200,{'pid':p.pid,'port':port,'status':status},subprocess.list2cmdline(cmd)+'; GET /rewrite/status');stop(p)
 mainhealth=req(8790,'/healthz')
 rec('G1_production_main_GET_identity',mainhealth['status']==200 and json.loads(mainhealth['body']).get('service')=='workbuddy2api',mainhealth,'GET http://127.0.0.1:8790/healthz (read-only)')
 cmd=[shutil.which('powershell.exe'),'-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'inventory.ps1')];cp=subprocess.run(cmd,capture_output=True,timeout=15)
 raw={'exit':cp.returncode,'stdout':cp.stdout.decode('utf-8',errors='replace'),'stderr':cp.stderr.decode('utf-8',errors='replace')};rec('production_inventory_phase1',cp.returncode==0,raw,subprocess.list2cmdline(cmd))
 rec('spec_lock_start_end',ids[str(BASE/'acceptance-spec.md')]==sha(BASE/'acceptance-spec.md') and ids[str(BASE/'acceptance-spec.lock')]==sha(BASE/'acceptance-spec.lock'),{'specSha256':sha(BASE/'acceptance-spec.md'),'lockSha256':sha(BASE/'acceptance-spec.lock')},'sha256 before/after G1 integration')
def main():
 if SCR.exists() or (ROOT/'g1-results.json').exists():raise RuntimeError('refuse to overwrite prior artifacts')
 try:suite()
 except Exception as e:rec('HARNESS_ERROR',False,{'error':str(e),'traceback':traceback.format_exc()},'python round2/g1_harness.py')
 finally:
  for p in PROCS:stop(p)
  for h in HANDLES:h.close()
  if SCR.exists():shutil.rmtree(SCR)
  rec('cleanup_g1',all(p.poll() is not None for p in PROCS),{'processes':[{'pid':p.pid,'exit':p.returncode,'running':p.poll() is None} for p in PROCS],'scratchRemoved':not SCR.exists(),'noPostSent':True},'finally: terminate only own Popen handles; remove only round2/scratch-g1')
  (ROOT/'g1-results.json').write_text(json.dumps(RECS,indent=2,ensure_ascii=False),encoding='utf-8')
  return 0 if all(r['pass'] for r in RECS) else 1
if __name__=='__main__':sys.exit(main())
