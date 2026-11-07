import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import http from 'node:http';
import {spawn,spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root=path.dirname(fileURLToPath(import.meta.url));
const python='C:/Users/ouquan/AppData/Local/Programs/Python/Python312/python.exe';
const stub='C:/temp/stub-rewrite.py';
const checks=[];let child=null;
try {
  for(const port of [8788,8789,8790]) {
    const r=spawnSync(python,[stub,'--port',String(port)],{encoding:'utf8',timeout:3000});
    const ok=r.status===2&&/reserved WB gateway ports/.test(r.stderr);
    checks.push({case:`reject-${port}`,ok,exit:r.status});if(!ok)throw new Error(`reserved port ${port} not rejected`);
  }
  await new Promise((resolve,reject)=>{const s=net.createServer();s.once('error',reject);s.listen(18791,'127.0.0.1',()=>s.close(resolve));});
  child=spawn(python,[stub],{stdio:['ignore','pipe','pipe']});
  let output='';await new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>reject(new Error('stub startup timeout')),4000);
    child.stdout.on('data',b=>{output+=b.toString();if(output.includes('127.0.0.1:18791')){clearTimeout(timer);resolve();}});
    child.once('exit',code=>{clearTimeout(timer);reject(new Error(`stub exited ${code}`));});
    child.once('error',e=>{clearTimeout(timer);reject(e);});
  });
  const response=await new Promise((resolve,reject)=>{const req=http.get('http://127.0.0.1:18791/rewrite/status',r=>{let b='';r.on('data',x=>b+=x);r.on('end',()=>resolve({status:r.statusCode,json:JSON.parse(b)}));});req.setTimeout(2000,()=>req.destroy(new Error('timeout')));req.on('error',reject);});
  const ok=response.status===200&&response.json.queued===0&&response.json.done===0&&response.json.failed===0;
  checks.push({case:'default-local-only-18791-empty-state',ok,response});if(!ok)throw new Error('unexpected isolated stub state');
} finally {
  if(child&&child.exitCode===null){const exited=new Promise(resolve=>child.once('exit',resolve));child.kill();await exited;}
}
const result={at:new Date().toISOString(),checks,childStopped:!child||child.exitCode!==null||child.signalCode!==null,productionInferenceRequests:0};
fs.writeFileSync(path.join(root,'stub-smoke-evidence.json'),JSON.stringify(result,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify(result,null,2));
