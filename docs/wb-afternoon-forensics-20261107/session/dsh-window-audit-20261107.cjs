const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const ROOT = process.env.DSH_HOME || 'C:/Users/ouquan/.dsh';
const FROM = Date.parse('2026-11-07T13:35:00+08:00');
const TO = Date.parse('2026-11-07T13:50:00+08:00');
const selectedWorkspaces = ['--D-deepseek~0020harness-work_anki_enligish--','--D-deepseek_v4.1flash1017-thebigfish--','--D-deepseek_v4.1flash1017-something_light--','--C-Users-ouquan-.dsh-bili-duty--','--C-Users-ouquan-.dsh-ungrouped--'];
function load(file) {
  const buf = fs.readFileSync(file), records = []; let offset = 0, frames = 0, residual = '';
  while (offset < buf.length) {
    const result = zlib.zstdDecompressSync(buf.subarray(offset), { info: true });
    if (!(result.engine.bytesWritten > 0)) throw Error('No frame progress');
    const text = residual + result.buffer.toString('utf8'), lines = text.split('\n'); residual = lines.pop();
    for (const line of lines) if (line.trim()) records.push(JSON.parse(line));
    offset += result.engine.bytesWritten; frames++;
  }
  if (residual.trim()) records.push(JSON.parse(residual));
  return { records, frames, bytes: buf.length, sha256: crypto.createHash('sha256').update(buf).digest('hex') };
}
function localTime(n) { return new Date(Number(n) + 8*3600000).toISOString().replace('Z','+08:00'); }
const mode = process.argv[2] || 'inventory';
const outputs = [];
for (const workspace of selectedWorkspaces) {
  const dir = path.join(ROOT,'sessions',workspace);
  if (!fs.existsSync(dir)) continue;
  for (const item of fs.readdirSync(dir,{withFileTypes:true})) {
    if (!item.isDirectory()) continue;
    const file = path.join(dir,item.name,'session.v4.jsonl.zstd');
    if (!fs.existsSync(file) || fs.statSync(file).mtimeMs < FROM) continue;
    try {
      const loaded = load(file), timed = loaded.records.filter(r=>Number.isFinite(r.time));
      const window = timed.filter(r=>r.time>=FROM && r.time<=TO);
      const types = {}; for (const r of window) types[r.type] = (types[r.type]||0)+1;
      const out = {file,bytes:loaded.bytes,sha256:loaded.sha256,frames:loaded.frames,totalRecords:loaded.records.length,first:timed.length?localTime(timed[0].time):null,last:timed.length?localTime(timed.at(-1).time):null,windowRecords:window.length,types};
      if (mode === 'result-shape') out.results = window.filter(r=>r.type==='tool/result' && [4869,4870,4871,5075,5074,5073].includes(r.seq)).map(r=>({seq:r.seq,time:localTime(r.time),message:r.data.message,meta:r.data.meta}));
      if (mode === 'tail-shape') out.results = window.filter(r=>r.time>=Date.parse('2026-11-07T13:45:37+08:00')).map(r=>({seq:r.seq,time:localTime(r.time),type:r.type,dataKeys:Object.keys(r.data||{}),messageKeys:Object.keys(r.data?.message||{}),meta:r.type==='tool/result'?r.data.meta:undefined,contentShape:Array.isArray(r.data?.message?.content)?r.data.message.content.map(c=>({type:c.type,keys:Object.keys(c)})):undefined}));
      if (mode === 'calls') out.calls = window.filter(r=>r.type==='tool/call').map(r=>{let args;try{args=JSON.parse(r.data.arguments)}catch{args={unparsed:true}};const sensitive=/token|authorization|password|secret|api.?key|cookie/i; const safe={};for(const [k,v] of Object.entries(args||{})){if(sensitive.test(k))safe[k]='[REDACTED]';else if(/command|cmd|script|job|pid|id|path|signal|reason/i.test(k))safe[k]=typeof v==='string'?v.replace(/(Bearer\s+)[A-Za-z0-9._~+\/-]+/gi,'$1[REDACTED]').replace(/((?:token|password|secret|api[_-]?key)\s*[:=]\s*)[^\s,;]+/gi,'$1[REDACTED]'):v;}return {seq:r.seq,time:localTime(r.time),name:r.data.name,callId:r.data.callId,argKeys:Object.keys(args||{}),args:safe}});
      if (mode === 'shape') out.shapes = window.filter(r=>/tool|exec|command|job/i.test(r.type)).map(r=>({seq:r.seq,time:localTime(r.time),type:r.type,keys:Object.keys(r.data||{}),shape:Object.entries(r.data||{}).map(([k,v])=>[k,Array.isArray(v)?'array':typeof v])}));
      outputs.push(out);
    } catch(e) { outputs.push({file,error:e.message}); }
  }
}
if (require.main === module) console.log(JSON.stringify({root:ROOT,from:localTime(FROM),to:localTime(TO),workspaces:selectedWorkspaces,files:outputs},null,2));
module.exports = {load, localTime};
