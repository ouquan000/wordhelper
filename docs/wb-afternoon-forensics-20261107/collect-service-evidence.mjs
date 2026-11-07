import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
const out = path.resolve('docs/wb-afternoon-forensics-20261107');
fs.mkdirSync(out,{recursive:true});
const sources = [
 {file:'D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/router.log',type:'utc'},
 {file:'D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/panel.log',type:'local'},
 {file:'D:/Apps/Bot/health/wb-guard.log',type:'guard'}
];
const result={observedAt:new Date().toISOString(),timezone:'Asia/Shanghai',purpose:'Afternoon outage forensics; read-only production access',sources:[]};
for(const s of sources){
 const bytes=fs.readFileSync(s.file), lines=bytes.toString('utf8').split(/\r?\n/);
 const selected=[];
 for(let i=0;i<lines.length;i++){
  const line=lines[i];
  const match=s.type==='utc'?line>='2026-11-07T05:42'&&line<'2026-11-07T05:53':s.type==='guard'?/^2026-11-07 13:(4\d|5[0-2])/.test(line):/^2026\/11\/07 13:(4\d|5[0-2])/.test(line);
  if(match)selected.push({line:i+1,text:line.replace(/(api_key=)[^,\s)]+/g,'$1[REDACTED]').replace(/acct=[^\s:]+/g,'acct=[REDACTED]').slice(0,1500)});
 }
 result.sources.push({path:s.file,bytes:bytes.length,sha256:crypto.createHash('sha256').update(bytes).digest('hex'),selected});
}
const files=[['router','D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/wb-router.js'],['starter','D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/start-silent.cmd'],['helper','D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/wb-service-check.ps1'],['guard','D:/deepseek_v4.1flash1017/thebigfish/tools/wb-gateway-guard.ps1']];
result.currentFileHashes=files.map(([name,p])=>({name,path:p,sha256:crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')}));
fs.writeFileSync(path.join(out,'service-evidence.json'),JSON.stringify(result,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({evidence:path.join(out,'service-evidence.json'),sources:result.sources.map(x=>({path:x.path,selectedLines:x.selected.length})),currentFileHashes:result.currentFileHashes},null,2));
