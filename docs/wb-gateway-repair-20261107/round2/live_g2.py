"""Production read-only final G2 after explicit parent reload notice. Secrets remain memory-only."""
import datetime,hashlib,http.client,json,pathlib,shutil,subprocess,sys,traceback
ROOT=pathlib.Path(__file__).resolve().parent;BASE=ROOT.parent;RECS=[]
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def rec(case,passed,raw,cmd):
 row={'case':case,'pass':bool(passed),'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'command':cmd,'raw':raw};RECS.append(row);print(json.dumps(row,ensure_ascii=False),flush=True)
def inventory():
 cmd=[shutil.which('powershell.exe'),'-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'inventory.ps1')]
 p=subprocess.run(cmd,capture_output=True,timeout=15);text=p.stdout.decode('utf-8-sig');assert p.returncode==0,p.stderr.decode('utf-8',errors='replace');return json.loads(text),subprocess.list2cmdline(cmd)
def get(port,path,key=None,kind='status'):
 assert port in (8788,8789,8790) and path in ('/healthz','/v1/models','/panel/','/_router/status','/status')
 c=http.client.HTTPConnection('127.0.0.1',port,timeout=10)
 try:
  headers={'Accept':'application/json'}
  if key:headers['Authorization']='Bearer '+key
  c.request('GET',path,headers=headers);r=c.getresponse();body=r.read()
  raw={'method':'GET','port':port,'path':path,'status':r.status,'contentType':r.getheader('Content-Type'),'bytes':len(body),'bodySha256':hashlib.sha256(body).hexdigest()}
  if kind=='health':
   j=json.loads(body);raw['json']={k:j.get(k) for k in ('service','healthy','total','realm_servable')}
  elif kind=='models':
   j=json.loads(body);ids=sorted(set(x['id'] for x in j.get('data',[]) if isinstance(x,dict) and isinstance(x.get('id'),str)));raw['sortedModelIdSet']=ids
  elif kind=='router':
   j=json.loads(body);raw['json']={k:j.get(k) for k in ('state','changedAt','main','aux','weight','hold','counters')};raw['qq']={k:j.get('qq',{}).get(k) for k in ('raw','rawHeldMs','lastProbeAt','lastProbeErr')}
  elif kind=='panel':raw['htmlRecognized']=b'<html' in body.lower() or b'<!doctype html' in body.lower()
  else:
   try:j=json.loads(body);raw['isJsonObject']=isinstance(j,dict)
   except ValueError:raw['isJsonObject']=False
  return raw
 finally:c.close()
def main():
 try:
  before,cmd=inventory();rec('G2_production_inventory_before',True,before,cmd)
  manifest=json.loads((BASE/'artifact-manifest.json').read_text(encoding='utf-8'))
  for item in manifest['artifacts']:
   if item.get('source'):
    target=pathlib.Path(item['target']);source=pathlib.Path(item['source']);expected=item['approvedSha256'];s=sha(source);t=sha(target)
    rec('formal_match_'+source.name,s==t==expected,{'source':str(source),'target':str(target),'approvedSha256':expected,'sourceSha256':s,'targetSha256':t},'sha256 independently read source and formal target '+str(target))
  # Deliberately never emit the config, header, key, response bodies of /status or panel.
  config_path=pathlib.Path('D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/router.config.json')
  config=json.loads(config_path.read_text(encoding='utf-8-sig'))
  mainkey=config['main']['key'];auxkey=config['aux']['key'];del config
  healths={}
  for port in (8790,8788,8789):
   out=get(port,'/healthz',kind='health');healths[port]=out
   rec('G2_health_'+str(port),out['status']==200 and out['json']['service']=='workbuddy2api' and isinstance(out['json']['healthy'],int) and out['json']['healthy']>0,out,'GET http://127.0.0.1:'+str(port)+'/healthz')
  models={}
  for port,key in ((8790,mainkey),(8788,mainkey),(8789,auxkey)):
   out=get(port,'/v1/models',key,'models');models[port]=out
   rec('G2_models_'+str(port),out['status']==200 and bool(out['sortedModelIdSet']),out,'GET http://127.0.0.1:'+str(port)+'/v1/models with memory-only local authentication')
  rec('G2_sorted_model_catalog_equal',models[8790]['sortedModelIdSet']==models[8788]['sortedModelIdSet'],{'mainIds':models[8790]['sortedModelIdSet'],'entryIds':models[8788]['sortedModelIdSet'],'comparison':'sorted unique ID set, no hardcoded count'},'compare independently collected sorted model ID sets 8790 vs 8788')
  for port in (8790,8788):
   out=get(port,'/panel/',kind='panel');rec('G2_panel_'+str(port),out['status']==200 and out['htmlRecognized'],out,'GET http://127.0.0.1:'+str(port)+'/panel/')
  out=get(8788,'/_router/status',kind='router');j=out['json'];rec('G2_router_health_observable',out['status']==200 and j['main']['healthy'] is True and j['aux']['healthy'] is True and j['main']['service']=='workbuddy2api' and j['aux']['service']=='workbuddy2api' and j['weight']=={'main':5,'aux':3},out,'GET http://127.0.0.1:8788/_router/status')
  for port,key in ((8790,mainkey),(8788,mainkey),(8789,auxkey)):
   out=get(port,'/status',key);rec('G2_status_'+str(port),out['status']==200 and out['isJsonObject'],out,'GET http://127.0.0.1:'+str(port)+'/status with memory-only local authentication; body excluded')
  del mainkey,auxkey
  after,cmd=inventory();rec('G2_production_inventory_after',True,after,cmd)
  b={p['ProcessId']:p for p in before['Owners']};a={p['ProcessId']:p for p in after['Owners']}
  protected=all(p in b and p in a and b[p]==a[p] for p in (38084,29624))
  entry=[s['OwningProcess'] for s in after['Listeners'] if s['LocalPort']==8788]
  rec('G2_protected_pools_and_reloaded_entry',protected and len(entry)==1 and entry[0]!=10576,{'protectedPidsUnchanged':protected,'mainBefore':b.get(38084),'mainAfter':a.get(38084),'auxBefore':b.get(29624),'auxAfter':a.get(29624),'entryOwnerPids':entry,'reloadNotice':'parent authorized reload reported at about 2026-11-07T12:28:20+08:00; this evidence is independently sampled'},'compare before/after CIM listener and creation-time snapshots')
  rec('K1_spec_lock_final_live',sha(BASE/'acceptance-spec.md')=='c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91' and sha(BASE/'acceptance-spec.lock')=='50f75118dffa481cd7299f17c6e2981b7dcc96252261cc1543382428bf0fe88b',{'specSha256':sha(BASE/'acceptance-spec.md'),'lockSha256':sha(BASE/'acceptance-spec.lock')},'sha256 frozen specification and lock after final online GET checks')
 except Exception as e:
  # Never include arbitrary exception details from credential handling.
  rec('LIVE_HARNESS_ERROR',False,{'errorType':type(e).__name__,'tracebackFrames':[{'file':f.filename,'line':f.lineno,'function':f.name} for f in traceback.extract_tb(e.__traceback__)]},'python round2/live_g2.py')
 finally:(ROOT/'live-g2-results.json').write_text(json.dumps(RECS,ensure_ascii=False,indent=2),encoding='utf-8')
 return 0 if all(r['pass'] for r in RECS) else 1
if __name__=='__main__':sys.exit(main())
