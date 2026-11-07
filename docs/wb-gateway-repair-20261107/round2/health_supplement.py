"""Additional strict fresh-probe validation using only this round's own primitives."""
import datetime,json,pathlib,shutil,subprocess,sys,time,traceback
import router_harness as r
ROOT=pathlib.Path(__file__).resolve().parent
r.SCR=ROOT/'scratch-health-supplement';r.EVD=ROOT/'health-supplement-evidence.jsonl'
def record(name,passed,raw,command=None):
 row={'case':name,'pass':bool(passed),'command':command or 'python round2/health_supplement.py (internal case '+name+')','raw':raw};r.RECS.append(row)
 with r.EVD.open('a',encoding='utf-8') as f:f.write(json.dumps(row,ensure_ascii=False)+'\n')
 print(json.dumps({'case':name,'pass':bool(passed)}),flush=True)
r.record=record
def fresh(router,pool,changed,reason,main,aux):
 def check():
  s=r.status(router.port);h=s[pool.pool]
  checked=datetime.datetime.fromisoformat(h['checkedAt'].replace('Z','+00:00')).timestamp() if h['checkedAt'] else 0
  return s if checked>=changed and h['reason']==reason and s['main']['healthy']==main and s['aux']['healthy']==aux else None
 return r.wait_until(check,5)
def main():
 if r.SCR.exists() or r.EVD.exists():raise RuntimeError('refuse overwrite')
 try:
  r.SCR.mkdir();cmd=['C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe','/nologo','/target:exe','/out:'+str(r.SCR/'netstat.exe'),str(ROOT/'FakeNetstat.cs')];cp=subprocess.run(cmd,capture_output=True,text=True)
  record('compile_supplement_netstat',cp.returncode==0,{'exit':cp.returncode,'stdout':cp.stdout,'stderr':cp.stderr},subprocess.list2cmdline(cmd));assert cp.returncode==0
  mainpool=r.fake_server('main');auxpool=r.fake_server('aux');router=r.Router('fresh-health',mainpool,auxpool)
  reasons={'wrong':'invalid_service','invalid_json':'invalid_json','not_found':'http_status_404','zero':'invalid_healthy','negative':'invalid_healthy','fraction':'invalid_healthy','string':'invalid_healthy','boolean':'invalid_healthy','missing':'invalid_healthy','oversize':'body_too_large','timeout':'timeout'}
  for pool in (mainpool,auxpool):
   for mode,reason in reasons.items():
    changed=time.time();pool.health=mode;s=fresh(router,pool,changed,reason,pool is auxpool,pool is mainpool);idx=len(r.EVENTS);out=r.request(router.port,'/v1/chat/completions','POST');sent=r.posts_since(idx)
    record('G5_fresh_'+pool.pool+'_'+mode,out['status']==200 and len(sent)==1 and sent[0]['pool']!=pool.pool,{'changedEpoch':changed,'status':s,'response':out,'posts':sent})
   changed=time.time();pool.health='ok';fresh(router,pool,changed,'ok',True,True)
  for selected,other in ((mainpool,auxpool),(auxpool,mainpool)):
   other.health='wrong';r.wait_health(router,selected is mainpool,selected is auxpool)
   for mode in ('404','429','reset','partial'):
    idx=len(r.EVENTS)
    try:out=r.request(router.port,'/v1/chat/completions','POST',mode,b'{"round2":"failure-on-'+selected.pool.encode()+b'"}')
    except Exception as e:out={'errorType':type(e).__name__,'error':str(e)}
    time.sleep(.13);sent=r.posts_since(idx)
    record('G6_each_pool_'+selected.pool+'_'+mode,len(sent)==1 and sent[0]['pool']==selected.pool and (mode not in ('404','429') or out.get('status')==int(mode)),{'response':out,'events':r.snap_events(idx)})
   other.health='ok';r.wait_health(router,True,True)
  qq=r.Router('qq-both-directions',mainpool,auxpool,'offline',False,150)
  (qq.dir/'qq-mode.txt').write_text('online');r.wait_until(lambda:r.status(qq.port) if r.status(qq.port)['state']=='online' else None,3)
  online=r.status(qq.port);(qq.dir/'qq-mode.txt').write_text('offline');r.wait_until(lambda:r.status(qq.port) if r.status(qq.port)['state']=='offline' else None,3);offline=r.status(qq.port)
  record('G5_QQ_both_transitions',online['qq']['raw'] is True and offline['qq']['raw'] is False,{'onlineStatus':online,'offlineStatus':offline,'netstatCalls':(qq.dir/'netstat-calls.txt').read_text(),'boundary':'accelerated 150ms holds in own config; candidate source defaults unmodified'})
 except Exception as e:record('SUPPLEMENT_HARNESS_ERROR',False,{'error':str(e),'traceback':traceback.format_exc()})
 finally:
  cleanup=[]
  for p in r.PROCS:
   if p.poll() is None:p.terminate();p.wait(5)
   cleanup.append({'pid':p.pid,'exit':p.returncode,'running':p.poll() is None})
  for srv in r.SERVERS:srv.shutdown();srv.server_close()
  if r.SCR.exists():shutil.rmtree(r.SCR)
  pycache=ROOT/'__pycache__'
  if pycache.exists():shutil.rmtree(pycache)
  record('cleanup_supplement',all(not x['running'] for x in cleanup),{'processes':cleanup,'scratchRemoved':not r.SCR.exists(),'poolPorts':[{'port':s.server_port,'accepting':r.port_open(s.server_port)} for s in r.SERVERS]})
  (ROOT/'health-supplement-results.json').write_text(json.dumps(r.RECS,ensure_ascii=False,indent=2),encoding='utf-8')
 return 0 if all(x['pass'] for x in r.RECS) else 1
if __name__=='__main__':sys.exit(main())
