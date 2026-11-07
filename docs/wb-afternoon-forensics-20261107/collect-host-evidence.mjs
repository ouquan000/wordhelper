import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
const root=path.resolve('docs/wb-afternoon-forensics-20261107');
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const result={observedAt:new Date().toISOString(),timeZone:'Asia/Shanghai',productionMutations:0,sources:[]};
const logs='C:/Users/ouquan/AppData/Roaming/dsh-tauri/logs/';
for(const name of ['desktop.log','desktop.frontdesk.log']){
 const file=logs+name,b=fs.readFileSync(file),a=b.toString('utf8').split(/\r?\n/);
 const selected=a.map((text,i)=>({line:i+1,text})).filter(x=>/2026-11-07 05:46:/.test(x.text)&&/Stopping Harness|Harness process spawned|STARTUP_READY|busyAction|serviceRunning/.test(x.text)).map(x=>({...x,text:x.text.replace(/([?&]token=)[^\s"<>]+/g,'$1[REDACTED]')}));
 result.sources.push({path:file,sha256:sha(b),bytes:b.length,selected});
}
const src='C:/Users/ouquan/AppData/Roaming/dsh-tauri/dependencies/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/lib/';
for(const [name,ranges] of [['index.js',[[1475,1508]]],['runner-launch-B2zsQ1Dz.js',[[717,750],[831,843]]]]){
 const file=src+name,b=fs.readFileSync(file),a=b.toString('utf8').split(/\r?\n/),s=fs.statSync(file);
 result.sources.push({path:file,sha256:sha(b),bytes:b.length,mtime:s.mtime.toISOString(),evidenceLevel:'Current installed mechanism only; not a per-PID historical termination audit',selected:ranges.flatMap(([start,end])=>a.slice(start-1,end).map((text,i)=>({line:start+i,text})))});
}
fs.writeFileSync(path.join(root,'host-restart-evidence.json'),JSON.stringify(result,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({evidence:'host-restart-evidence.json',sources:result.sources.map(x=>({path:x.path,lines:x.selected.length,sha256:x.sha256}))},null,2));
