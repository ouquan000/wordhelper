import http from 'node:http';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = path.dirname(fileURLToPath(import.meta.url));
function get(port, endpoint, auth = false) {
  return new Promise(resolve => {
    const req = http.get({host:'127.0.0.1',port,path:endpoint,headers:auth?{Authorization:'Bearer sk-wb-local-8788'}:{}}, res => {
      const parts=[]; let size=0;
      res.on('data', b => { size+=b.length; if(size>8*1024*1024) req.destroy(new Error('response too large')); else parts.push(b); });
      res.on('end',()=>{
        const text=Buffer.concat(parts).toString('utf8'); let data=null; try{data=JSON.parse(text);}catch{}
        const result={port,path:endpoint,status:res.statusCode,bytes:size};
        if(endpoint==='/healthz') Object.assign(result,{service:data?.service,healthy:data?.healthy,total:data?.total});
        else if(endpoint==='/v1/models') Object.assign(result,{modelCount:data?.data?.length,modelIds:data?.data?.map(x=>x.id).sort(),sha256:crypto.createHash('sha256').update(text).digest('hex')});
        else if(endpoint==='/_router/status') Object.assign(result,{routerStatus:data});
        else if(endpoint==='/panel/') Object.assign(result,{html:/<html|<!doctype/i.test(text),title:text.match(/<title>(.*?)<\/title>/is)?.[1]});
        else Object.assign(result,{keys:data?Object.keys(data):[],overview:data});
        resolve(result);
      });
    });
    req.setTimeout(4000,()=>req.destroy(new Error('timeout')));
    req.on('error',e=>resolve({port,path:endpoint,error:e.message}));
  });
}
const results=await Promise.all([
  get(8790,'/healthz'),get(8788,'/healthz'),get(8789,'/healthz'),
  get(8790,'/v1/models',true),get(8788,'/v1/models',true),get(8788,'/panel/'),get(8788,'/_router/status')
]);
const main=results.find(x=>x.port===8790&&x.path==='/v1/models');
const entry=results.find(x=>x.port===8788&&x.path==='/v1/models');
const output={at:new Date().toISOString(),productionInferenceRequests:0,modelsMatch:!!main?.modelIds&&JSON.stringify(main.modelIds)===JSON.stringify(entry?.modelIds),results};
const out=process.argv[2] || 'live-get-evidence.json';
fs.writeFileSync(path.join(root,out),JSON.stringify(output,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({...output,results:results.map(({modelIds,routerStatus,overview,...r})=>({...r,...(routerStatus?{routerStatus}:{}),...(overview?{overviewKeys:Object.keys(overview)}:{})}))},null,2));
if(results.some(x=>x.status!==200)||!output.modelsMatch) process.exitCode=1;
