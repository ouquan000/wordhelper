"""Final independent production GET and frozen-file recheck. Never POST or stop."""
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from datetime import datetime

OUT=Path(__file__).resolve().parent
BASE=OUT.parent
RUN=Path('D:/deepseek_v4.1flash1017/workbuddy2api-panel/run')
PS=Path(os.environ['SystemRoot'])/'System32/WindowsPowerShell/v1.0/powershell.exe'
EXPECTED_SPEC='c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91'
EXPECTED_LOCK='50f75118dffa481cd7299f17c6e2981b7dcc96252261cc1543382428bf0fe88b'
report={'startedAt':datetime.now().astimezone().isoformat(),'checks':[],'requests':[],'productionPosts':0,'processStops':0}
secret=''
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def check(name,ok,detail=None): report['checks'].append({'name':name,'passed':bool(ok),'detail':detail})
def freeze(): return {'spec':sha(BASE/'acceptance-spec.md'),'lock':sha(BASE/'acceptance-spec.lock'),'candidates':{p.name:sha(p) for p in (BASE/'candidate').iterdir() if p.is_file() and p.name in ('wb-router.js','start-silent.cmd','wb-service-check.ps1','wb-gateway-guard.ps1')}}
def get(port,path,auth=False):
    assert port in (8788,8789,8790) and path in ('/healthz','/v1/models','/panel/','/_router/status')
    c=http.client.HTTPConnection('127.0.0.1',port,timeout=12)
    started=time.monotonic()
    try:
        c.request('GET',path,headers={'Authorization':'Bearer '+secret} if auth else {})
        response=c.getresponse(); body=response.read()
        item={'method':'GET','port':port,'path':path,'authenticated':auth,'status':response.status,'bytes':len(body),'bodySha256':hashlib.sha256(body).hexdigest(),'elapsedMs':round((time.monotonic()-started)*1000)}
        if path=='/healthz': item['json']=json.loads(body)
        elif path=='/v1/models':
            data=json.loads(body); ids=[m.get('id') for m in data.get('data',[])]; item.update(modelIds=sorted(ids),count=len(ids),valid=bool(ids) and all(isinstance(x,str) and x for x in ids))
        elif path=='/_router/status': item['topLevelFields']=sorted(json.loads(body))
        elif path=='/panel/': item['contentType']=response.getheader('content-type')
        report['requests'].append(item); return item
    finally:c.close()

def snapshot():
    script='$ports=@(8788,8789,8790); $listeners=@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object {$ports -contains $_.LocalPort} | ForEach-Object {$p=Get-CimInstance Win32_Process -Filter ("ProcessId="+$_.OwningProcess); [pscustomobject]@{port=$_.LocalPort;pid=$_.OwningProcess;exe=$p.ExecutablePath;created=$p.CreationDate.ToString("o");commandLine=$p.CommandLine}}); $fixtures=@(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {$_.ProcessId -ne $PID -and ($_.CommandLine -like "*.runtime-g34-*" -or $_.CommandLine -like "*.runtime-g56-*")} | Select-Object ProcessId,ExecutablePath,CommandLine); [pscustomobject]@{listeners=$listeners;isolatedFixtures=$fixtures}|ConvertTo-Json -Depth 8 -Compress'
    r=subprocess.run([str(PS),'-NoProfile','-NonInteractive','-Command',script],capture_output=True,timeout=20)
    if r.returncode: raise RuntimeError('final native snapshot nonzero '+str(r.returncode))
    return json.loads(r.stdout.decode('utf-8-sig'))

try:
    report['hashesBegin']=freeze(); check('begin-spec-lock-exact',report['hashesBegin']['spec']==EXPECTED_SPEC and report['hashesBegin']['lock']==EXPECTED_LOCK,report['hashesBegin'])
    report['nativeBefore']=snapshot()
    config_before=sha(RUN/'router.config.json')
    secret=json.loads((RUN/'router.config.json').read_text(encoding='utf-8-sig'))['main']['key']
    if not isinstance(secret,str) or not secret: raise RuntimeError('local existing key missing')
    health={p:get(p,'/healthz') for p in (8790,8788,8789)}
    for p in (8790,8788): check('main-entry-health-'+str(p),health[p]['status']==200 and health[p]['json'].get('service')=='workbuddy2api' and health[p]['json'].get('healthy')==7,health[p])
    check('aux-health-original-one',health[8789]['status']==200 and health[8789]['json'].get('service')=='workbuddy2api' and health[8789]['json'].get('healthy')==1,health[8789])
    main=get(8790,'/v1/models',True); entry=get(8788,'/v1/models',True)
    check('authenticated-model-ID-sets-equal',main['status']==entry['status']==200 and main['valid'] and entry['valid'] and sorted(set(main['modelIds']))==sorted(set(entry['modelIds'])),{'mainCount':main['count'],'entryCount':entry['count'],'countIsObservation':True,'onlyMain':sorted(set(main['modelIds'])-set(entry['modelIds'])),'onlyEntry':sorted(set(entry['modelIds'])-set(main['modelIds']))})
    panel=get(8788,'/panel/'); check('entry-panel-200',panel['status']==200,panel)
    status=get(8788,'/_router/status'); check('entry-status-200',status['status']==200,status)
    report['nativeAfter']=snapshot()
    baseline=json.loads((OUT/'stage12-evidence.json').read_text(encoding='utf-8'))['listeners_before']
    before={x['port']:x for x in baseline}; after={x['port']:x for x in report['nativeAfter']['listeners']}
    for p in (8790,8789): check('original-pool-identity-preserved-'+str(p),all(before[p][k]==after[p][k] for k in ('pid','exe','created','commandLine')),{'baseline':before[p],'final':after.get(p)})
    check('read-window-entry-identity-stable',report['nativeBefore']['listeners']==report['nativeAfter']['listeners'],{'before':report['nativeBefore']['listeners'],'after':report['nativeAfter']['listeners']})
    check('no-owned-isolated-fixture-processes',not report['nativeAfter']['isolatedFixtures'],report['nativeAfter']['isolatedFixtures'])
    check('read-only-router-config-unchanged',config_before==sha(RUN/'router.config.json'),{'sha256':sha(RUN/'router.config.json')})
    helper=RUN/'wb-service-check.ps1'
    report['observedFormalHelper']={'exists':helper.exists(),'sha256':sha(helper) if helper.exists() else None}
except Exception as e:
    report['error']={'type':type(e).__name__,'message':str(e).replace(secret,'[REDACTED]') if secret else str(e)}
    check('final-readonly-completed',False,report['error'])
finally:
    report['hashesEnd']=freeze(); check('end-spec-lock-candidates-exact',report['hashesBegin']==report['hashesEnd'] and report['hashesEnd']['spec']==EXPECTED_SPEC and report['hashesEnd']['lock']==EXPECTED_LOCK,report['hashesEnd'])
    report['endedAt']=datetime.now().astimezone().isoformat()
    text=json.dumps(report,ensure_ascii=False,indent=2)
    if secret and secret in text: raise RuntimeError('secret redaction invariant failed')
    (OUT/'final-readonly-evidence.json').write_text(text,encoding='utf-8')
print(json.dumps({'passed':sum(x['passed'] for x in report['checks']),'total':len(report['checks']),'failed':[x for x in report['checks'] if not x['passed']],'observedFormalHelper':report.get('observedFormalHelper'),'evidence':str(OUT/'final-readonly-evidence.json')},ensure_ascii=False))
sys.exit(0 if all(x['passed'] for x in report['checks']) else 1)
