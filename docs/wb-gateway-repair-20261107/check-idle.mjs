import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.dirname(fileURLToPath(import.meta.url));
const cfg=JSON.parse(fs.readFileSync('D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/router.config.json','utf8').replace(/^\uFEFF/,''));
function inspect(port,key) {
  return new Promise(resolve=>{
    const req=http.get({host:'127.0.0.1',port,path:'/status',headers:{Authorization:`Bearer ${key}`}},res=>{
      const chunks=[];let size=0;
      res.on('data',b=>{size+=b.length;if(size>1024*1024)req.destroy(new Error('oversize'));else chunks.push(b);});
      res.on('end',()=>{try{const j=JSON.parse(Buffer.concat(chunks));const rows=j.accounts;if(res.statusCode!==200||!Array.isArray(rows))throw new Error(`invalid status ${res.statusCode}`);resolve({port,status:res.statusCode,accountCount:rows.length,healthy:j.healthy,inFlight:rows.reduce((n,r)=>n+Number(r.in_flight||0),0)});}catch(e){resolve({port,error:e.message});}});
    });
    req.setTimeout(3000,()=>req.destroy(new Error('timeout')));req.on('error',e=>resolve({port,error:e.message}));
  });
}
const rows=await Promise.all([inspect(8790,cfg.main?.key||'sk-wb-local-8788'),inspect(8789,cfg.aux?.key||'sk-wb-local-8789')]);
const out={at:new Date().toISOString(),productionInferenceRequests:0,pools:rows,allIdle:rows.every(r=>r.status===200&&r.inFlight===0)};
if(process.argv[2]) fs.writeFileSync(path.join(root,process.argv[2]),JSON.stringify(out,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(out,null,2));
if(rows.some(r=>r.error))process.exitCode=1;
