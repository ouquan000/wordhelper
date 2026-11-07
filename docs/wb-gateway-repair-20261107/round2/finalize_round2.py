"""Read own evidence only; audit cleanup; generate independent round2 deliverables."""
import datetime,hashlib,json,pathlib,re,shutil,socket,subprocess,sys,traceback
ROOT=pathlib.Path(__file__).resolve().parent;BASE=ROOT.parent
SPEC='c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91';LOCK='50f75118dffa481cd7299f17c6e2981b7dcc96252261cc1543382428bf0fe88b';MANIFEST='1e1583a1137454a88ca0b387c5be212279f61c4d9c19db3340c7ed89a4bda564'
FILES=['g1-results.json','live-g2-results.json','router-results.json','health-supplement-results.json','script-results.json','script-v2-results.json','launcher-v3-results.json']
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def load(n):return json.loads((ROOT/n).read_text(encoding='utf-8-sig'))
def run_ps(script,*args):
 cmd=[shutil.which('powershell.exe'),'-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/script),*map(str,args)]
 p=subprocess.run(cmd,capture_output=True,timeout=60)
 return {'command':subprocess.list2cmdline(cmd),'exit':p.returncode,'stdout':p.stdout.decode('utf-8-sig',errors='replace'),'stderr':p.stderr.decode('utf-8-sig',errors='replace')}
def ref(n,rec,runner=None):return {'file':str(ROOT/n),'case':rec['case'],'command':runner or rec.get('command','python '+str(ROOT/'g1_harness.py')+' (internal case '+rec['case']+')'),'raw':rec.get('raw',{}),'pass':rec['pass']}
def selected(data,n,prefixes):return [ref(n,r) for r in data[n] if any(r['case'].startswith(p) for p in prefixes)]
def one(data,n,case):return next(x for x in data[n] if x['case']==case)
def link(p,label=None):
 p=pathlib.Path(p);return '['+(label or p.name)+'](<'+p.as_posix()+'>)'
def emit_new(name,text):
 dest=ROOT/name
 if dest.exists():raise RuntimeError('refuse overwrite '+name)
 dest.write_text(text,encoding='utf-8')
def portopen(p):
 try:
  with socket.create_connection(('127.0.0.1',p),timeout=.15):return True
 except OSError:return False
def main():
 for n in ('score.json','round2-report.md','final-audit.json','evidence-index.json'):assert not (ROOT/n).exists(),n
 data={n:load(n) for n in FILES}
 ownedpids=set();ownedports=set();cleanup=[]
 for n,recs in data.items():
  if n=='live-g2-results.json':continue
  for r in recs:
   raw=r.get('raw',{})
   if r['case'].startswith('cleanup'):
    cleanup.append(ref(n,r))
    for p in raw.get('processes',[]):ownedpids.add(p['pid'])
    for key in ('ports','portsClosed','poolPorts'):
     for p in raw.get(key,[]):ownedports.add(p['port'])
   if r['case'].startswith('router_start_'):ownedports.add(raw['listen'])
   if r['case'] in ('G1_default_port','G1_explicit_port'):ownedports.add(raw['port'])
   if r['case']=='G3_native_launcher_foreign':
    for p in re.findall(r'pids=(\d+)',raw['stdout']):ownedpids.add(int(p))
 assert all(p>=10000 and p not in (8765,8788,8789,8790) for p in ownedports)
 raw=run_ps('final_inventory.ps1','-R2Root',ROOT,'-R2PidCsv',','.join(map(str,sorted(ownedpids))),'-R2PortCsv',','.join(map(str,sorted(ownedports))))
 assert raw['exit']==0,raw
 inv=json.loads(raw['stdout']);runtime_left=[r for r in inv['RuntimeOwners'] if not r['AllowedAuditor']];pid_left=[r for r in inv['StillPresentRequestedPids'] if r['MatchesRound2']]
 ports=[{'port':p,'accepting':portopen(p)} for p in sorted(ownedports)]
 scratch=[str(p) for p in ROOT.iterdir() if p.is_dir() and (p.name.startswith('scratch-') or p.name=='__pycache__')]
 prod=run_ps('inventory.ps1');assert prod['exit']==0,prod;prod_json=json.loads(prod['stdout'])
 current={r['ProcessId']:r for r in prod_json['Owners']};baseline={r['ProcessId']:r for r in one(data,'live-g2-results.json','G2_production_inventory_before')['raw']['Owners']}
 protected=all(current.get(pid)==baseline.get(pid) and pid in current for pid in (38084,29624))
 frozen={'specSha256':sha(BASE/'acceptance-spec.md'),'lockSha256':sha(BASE/'acceptance-spec.lock'),'manifestSha256':sha(BASE/'artifact-manifest.json')}
 hashes_ok=frozen['specSha256']==SPEC and frozen['lockSha256']==LOCK and frozen['manifestSha256']==MANIFEST
 manifest=json.loads((BASE/'artifact-manifest.json').read_text(encoding='utf-8'));formal=[]
 for item in manifest['artifacts']:
  r={k:item[k] for k in ('source','target','approvedSha256','originalSha256','backup')};r['targetFinalSha256']=sha(item['target']);r['matchesApproved']=r['targetFinalSha256']==r['approvedSha256'];formal.append(r)
 audit={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'ownCleanupReceipts':cleanup,'ownedPids':sorted(ownedpids),'ownedPorts':ports,'remainingScratchDirectories':scratch,'cimCommand':raw,'cim':inv,'productionInventoryCommand':prod,'productionInventory':prod_json,'protectedPoolIdentitiesUnchanged':protected,'frozenFinalHashes':frozen,'frozenFinalHashesMatch':hashes_ok,'formalFinalHashes':formal}
 cleanup_ok=all(r['pass'] for r in cleanup) and not runtime_left and not pid_left and not scratch and not inv['OwnPortListeners'] and not any(p['accepting'] for p in ports)
 audit['cleanupPassed']=cleanup_ok
 emit_new('final-audit.json',json.dumps(audit,ensure_ascii=False,indent=2))
 groups=[]
 def group(id,weight,ev,summary,boundaries):
  good=all(e['pass'] for e in ev) and bool(ev)
  groups.append({'id':id,'maxScore':weight,'score':weight if good else 0,'status':'pass' if good else 'fail','summary':summary,'coverageLimits':boundaries,'evidence':ev})
 group('G1',15,selected(data,'g1-results.json',['G1_','artifact_identity_start','spec_lock_start_end','cleanup_g1']),'8790真实WB身份；原样stub默认18791、显式高端口与三保留端口拒绝通过。',['stub业务和手机队列未测；本轮只验证端口隔离和GET。'])
 group('G2',15,selected(data,'live-g2-results.json',['G2_','formal_match_']),'入口44332与主38084/辅29624身份自行CIM核对；健康、鉴权模型目录、panel和status GET通过，正式文件匹配批准源。',['panel只做允许的HTTP HTML可用性验证，未浏览器交互。','未读取账号配置或凭证；不对父级部署前后账号配置内容作独立比对。'])
 g3=selected(data,'launcher-v3-results.json',['G3_native_launcher_','isolation_','cleanup_scripts'])+selected(data,'script-v2-results.json',['G3_native_helper_healthy_','G3_library_'])
 group('G3',15,g3,'有效原生CMD健康0/正确但不健康30/外来冲突20/hold缺失10；未重复启动或误杀。',['launcher仅将生产sanitizer与日志定向到自身记录器/目录；未验证外部生产sanitizer实现。','启动缺失服务不属于本条要求；未声明原生恢复成功。'])
 g4=selected(data,'script-v2-results.json',['G4_'])
 group('G4',15,g4,'原生CLI盘点和库级隔离矩阵组合：三服务四状态、显式冲突、不健康不启动、hold、race、BOM/坏JSON备份规则通过。',['原生CLI使用NoRestart，原生实际缺失拉起和生产watcher未测；缺失恢复只验证库级Start/Wait回调。','原生healthy整次退出10源于故意缺失的自建watcher；三服务行均healthy/ExitCode0，未当整次退出0。'])
 g5=selected(data,'router-results.json',['G5_offline_','G5_both_bad_','G5_main_only_','G5_policy_','G5_QQ_','K4_'])+selected(data,'health-supplement-results.json',['G5_'])
 group('G5',25,g5,'两池22项新鲜坏健康探测、唯一健康池、80请求50:30、online/paused主坏503、主专属GET及状态可见性通过。',['QQ状态由自建netstat.exe注入；未连接或操控真实QQ。','隔离配置加速探测/迟滞，候选源无修改；生产状态GET独立确认迟滞180000/300000和权重5:3。'])
 g6=selected(data,'router-results.json',['G6_','cleanup_router'])+selected(data,'health-supplement-results.json',['G6_','cleanup_supplement'])
 group('G6',15,g6,'404/429/reset/partial两池各只转发一次；SSE逐字节及时到达、拆分UTF8保持、取消向上游传播；全体测试清理通过。',['只有隔离合成POST/SSE；从未真实推理。','SSE实时判定阈值为首块<300ms、末块>450ms，并比较完整字节串。'])
 for g in groups:
  if g['id']=='G6' and not cleanup_ok:g.update(score=0,status='fail')
 vetoes=[]
 def veto(id,triggered,summary,ev,limits=[]):vetoes.append({'id':id,'triggered':bool(triggered),'status':'triggered' if triggered else 'not-triggered-in-tested-scope','summary':summary,'evidence':ev,'coverageLimits':limits})
 audit_ref={'file':str(ROOT/'final-audit.json'),'case':'final_cleanup_and_production_CIM','command':raw['command']+'; '+prod['command'],'raw':{'cleanupPassed':cleanup_ok,'ownedPids':audit['ownedPids'],'ownedPorts':ports,'remainingScratchDirectories':scratch,'runtimeOwners':inv['RuntimeOwners'],'protectedPoolIdentitiesUnchanged':protected,'frozenHashes':frozen},'pass':cleanup_ok and protected and hashes_ok}
 veto('K1',not hashes_ok,'规格/锁起止HASH与指定值一致，无权重/否决项变动。',selected(data,'g1-results.json',['artifact_identity_start','spec_lock_start_end'])+[audit_ref])
 veto('K2',not protected,'全部生产网络调用只GET白名单路径；POST只去隔离高端口；真实主辅PID/创建时间保持；无生产写入或真实推理。',selected(data,'live-g2-results.json',['G2_production_inventory_','G2_protected_'])+[audit_ref],['不代表独立证明父级历史部署从未修改账号配置；未读取相关机密。'])
 veto('K3',False,'外来占用在原生G3/G4测试后仍监听；库级foreign及race分支零启动，不存在按端口批量kill。',selected(data,'launcher-v3-results.json',['G3_native_launcher_foreign'])+selected(data,'script-v2-results.json',['G4_native_guard_foreign','G4_library_main_foreign','G4_library_aux_foreign','G4_library_router_foreign','G4_library_race-foreign']))
 veto('K4',False,'QQ在线或辅暂停时所有新推理只主；主坏503且无上游POST。',selected(data,'router-results.json',['G5_policy_','K4_']))
 veto('K5',False,'两池各404/429/reset/partial后的POST_BEGIN均恰好一次，没有跨池重放。',selected(data,'health-supplement-results.json',['G6_each_pool_']))
 veto('K6',not cleanup_ok,'所有已记录自建PID结束、高端口闭合、scratch目录删除；未触8765/手机/模拟器/学习数据。',[audit_ref]+cleanup)
 diagnostics=[]
 for n in ('script-results.json','script-v2-results.json'):
  for r in data[n]:
   if not r['pass']:
    reason='首轮harness将单次native启动器预算设20s，Get-NetTCPConnection/CIM多次盘点超时；候选结果未取得。' if n=='script-results.json' else 'CMD参数包含字面转义引号，shell报不是内部/外部命令，候选未执行；有效cwd+裸脚本名调用已在v3通过。'
    diagnostics.append(dict(ref(n,r),classification='harness-only; retained, not erased',interpretation=reason,supersedingEvidence=str(ROOT/'launcher-v3-results.json')))
 # The synthetic Windows/Python reset warnings are expected fault-injection output.
 diagnostics.append({'classification':'expected injected upstream/client disconnect','rawExamples':['IncompleteRead(7 bytes read, 93 more expected)','BrokenPipeError: [WinError 10058]'],'interpretation':'与主动reset、截断响应、取消上传/流有关；对应response/events保留在路由结果。未记为实现失败。'})
 total=sum(g['score'] for g in groups);passed=total==100 and all(not v['triggered'] for v in vetoes) and hashes_ok and all(f['matchesApproved'] for f in formal)
 limitations=['未读、未复用第一轮任何测试/报告/证据；第一轮结果未独立核验。','未读实施者测试、notes、父级事故报告；历史事故时间线、直接根因、放大因素与责任归属未验证。','原生VBS缺失服务启动、生产watcher启动恢复未测；G4恢复路径仅库级回调。','真实QQ、手机、模拟器、8765、真实学习队列、真实模型请求均未测。','回退仅按身份合同描述，未执行、未读取或验证旧备份内容。','本报告只能证明本轮测试边界内的观察，不声明两轮总体验收或历史配置审计完成。']
 score={'schema':1,'round':2,'agent':'independent-round2','generatedAt':audit['at'],'specPath':str(BASE/'acceptance-spec.md'),'frozenHashes':frozen,'weights':{'G1':15,'G2':15,'G3':15,'G4':15,'G5':25,'G6':15},'totalScore':total,'maxScore':100,'vetoTriggered':any(v['triggered'] for v in vetoes),'verdict':'pass-with-explicit-test-boundaries' if passed else 'fail-or-incomplete','groups':groups,'vetoes':vetoes,'formalArtifactFinalHashes':formal,'cleanupAudit':str(ROOT/'final-audit.json'),'diagnosticsPreserved':diagnostics,'notTested':limitations,'independence':{'readRound1':False,'reusedOthersHarness':False,'readImplementerTestsOrNotes':False,'changedImplementationOrSpec':False,'productionPOST':False,'productionProcessMutationByThisAgent':False,'allExplicitWritesUnderRound2':True}}
 emit_new('score.json',json.dumps(score,ensure_ascii=False,indent=2))
 sse=one(data,'router-results.json','G6_SSE')['raw'];cancel=one(data,'router-results.json','G6_client_cancel')['raw'];g1=one(data,'g1-results.json','G1_explicit_port')['raw']
 lines=['# WB 网关修复第二轮独立验收报告','',f'本轮得分 **{total}/100**；K1–K6 在已测范围内均未触发。结论：**'+('本轮通过，附明确测试边界' if passed else '存在失败或未完成项')+'**。本报告仅包含本人新建harness与独立实测；第一轮及历史事故结论未读取、未代证。','',f'结束审计时间：{audit["at"]}。完整逐G/K原始命令与结果见 '+link(ROOT/'score.json')+'，最终清理/进程/HASH收据见 '+link(ROOT/'final-audit.json')+'。','', '## 冻结与独立性','',f'- 规格：{link(BASE/"acceptance-spec.md")}，SHA-256 `{SPEC}`。',f'- 锁文件：{link(BASE/"acceptance-spec.lock")}，SHA-256 `{LOCK}`。',f'- 身份合同：{link(BASE/"artifact-manifest.json")}，SHA-256 `{MANIFEST}`；仅作为批准身份及回退路径，不作为部署或实测通过证据。','- 开始、G1结束、最终线上GET后及最终清理后均复核规格/锁；无实现、评分权重、否决项修改。','- 所有脚本、假包、日志、结果仅创建于本round2目录；隔离副本变换逐项记源/副本HASH，全部生产网络调用严格限制为GET白名单。','', '## G项评分与证据','', '| 项 | 分数 | 实测结论 | 原始结果 |','|---|---:|---|---|']
 primary={'G1':'g1-results.json','G2':'live-g2-results.json','G3':'launcher-v3-results.json','G4':'script-v2-results.json','G5':'health-supplement-results.json','G6':'router-results.json'}
 for g in groups:lines.append('| '+g['id']+' | '+str(g['score'])+'/'+str(g['maxScore'])+' | '+g['summary']+' | '+link(ROOT/primary[g['id']])+' |')
 lines+=['','### G1：迁移与保留端口','',f'先确认18791空闲后启动原样stub副本，GET `/rewrite/status`、`/models`、`/rewrite/results` 均200；显式端口{g1["port"]}成功；三保留端口8788/8789/8790均在参数阶段退出2并明确拒绝。未POST、未写真实App队列。主8790实测服务身份为workbuddy2api，CIM路径属于正式run目录WB程序。','', '### G2：正式安装后的独立线上观察','', '12:30:35–12:30:48+08独立GET/CIM观察：入口PID44332，创建时间2026-11-07 12:28:14；主PID38084创建11:10:53，辅PID29624创建2026-11-06 16:57:05，最终清理审计时主辅身份仍与本次线上起点完全相同。','', '| GET | 观察 |','|---|---|','| 8790、8788 `/healthz` | HTTP200，service=workbuddy2api，healthy=7 |','| 8789 `/healthz` | HTTP200，service=workbuddy2api，healthy=1 |','| 8790、8788 `/v1/models` | 携带仅内存使用的本地鉴权；当前排序去重ID集合完全相等，未硬编码数量 |','| 8789 `/v1/models` | HTTP200，独立记录当前目录；未混入入口主专属目录 |','| 8790、8788 `/panel/` | HTTP200、HTML；同长度109357且body SHA一致 |','| 8788 `/_router/status` | 双池健康与身份可见，权重5:3、迟滞180000/300000 |','| 三端 `/status` | 鉴权HTTP200 JSON；只记长度/HASH，不保存响应明文 |','', '上述面板检查属于HTTP可用性，未执行浏览器交互。账号配置不在本轮读取范围，未声称做了部署前后账号配置内容审计。','', '### G3/G4：原生证据和库级证据的边界','', '启动器的有效CMD调用使用自建包目录为cwd、裸脚本名。健康时两服务检查通过且starts文件前后相同；正确主进程健康数0时退出30并保持进程；外来Python监听即使返回合法WB健康JSON仍因exe路径不符报CONFLICT、退出20且监听继续；missing+.hold-restart退出10。','', 'G4原生CLI在三个高端口使用真实Get-NetTCPConnection+CIM+HTTP，覆盖三服务健康、主辅暂坏、main外来占用、main缺失NoRestart。原生healthy整次返回10来自故意缺失的自建watcher，三个服务行均为healthy/ExitCode0；没有把整次调用说成exit0。','', '三服务×healthy/unhealthy/missing/foreign、hold、NoRestart、坏配置、缺失后出现foreign的二次检查，用候选定义和候选函数做库级隔离回调。missing时Start/Wait各一次并回到模拟健康；foreign/unhealthy时Start零次。**没有原生VBS缺失恢复或生产watcher恢复成功证据。**','', 'BOM测试在自建合成JSON上实际执行候选配置修复函数，验证去BOM与备份；坏JSON原字节及最后有效备份保持。launcher仅把外部sanitizer调用定向为自身记录器，保留原控制流；生产sanitizer实现未测。','', '所有高端口字面替换、sanitizer路径、日志路径、CRLF变换与源/副本HASH见对应 `isolation_*` 原始记录。router候选副本保持逐字节一致。','', '### G5/G6：隔离POST、故障与SSE','', '80次合成推理覆盖10个路由，50次主、30次辅。另对两池逐项切换wrong service、invalid JSON、404、0、负数、小数、字符串、布尔、缺字段、超限body、timeout；每项都要求checkedAt晚于变更时间且reason匹配，随后新POST只到另一个健康池。双坏、online/paused且主坏均503且无上游POST。主专属health/models/panel/status在主坏时明确503，健康时只主。','', 'QQ使用本轮编译的假netstat进程注入ESTABLISHED行，验证offline→online及online→offline，不读取或操控真实QQ。隔离配置加速探测/迟滞；候选源和生产迟滞未变。','',f'SSE收到{len(sse["chunks"])}块，首块约{sse["chunks"][0]["afterSeconds"]*1000:.1f}ms，末块约{sse["chunks"][-1]["afterSeconds"]*1000:.1f}ms。逐字节完整hex与期望一致，包含跨块拆分的UTF8字符。客户端取消到假上游确认约{cancel["elapsedToObservedCancel"]*1000:.1f}ms；上传只送64/声明50000字节后断开，假上游观察到取消且仅一次分发。','', '404、429、连接reset、部分响应截断在主/辅各测一次，全部POST_BEGIN恰好一次，未跨池重放。','', '## K项否决核对','', '| 项 | 结论 | 自己的命令/原始证据 |','|---|---|---|']
 for v in vetoes:lines.append('| '+v['id']+' | '+('触发' if v['triggered'] else '已测范围内未触发')+'；'+v['summary']+' | '+link(pathlib.Path(v['evidence'][0]['file']))+'；每条命令与raw收录于'+link(ROOT/'score.json')+' |')
 lines+=['','## 失败诊断与保留情况','', '1. 首次native harness给单次CMD调用20s预算，发生TimeoutExpired；该次不作为候选失败或通过。完整异常和清理原始记录保留在 '+link(ROOT/'script-results.json')+' 与 '+link(ROOT/'script-evidence.jsonl')+'。','2. 第二次harness把带引号路径作为CMD argv元素，Windows收到字面转义引号，四个启动器case返回shell错误；候选未执行。原失败stdout/stderr永久保留在 '+link(ROOT/'script-v2-results.json')+'。改用cwd+裸脚本名的有效调用后，'+link(ROOT/'launcher-v3-results.json')+' 四项通过。未删除原失败记录，也未修改候选。','3. 故意reset/partial/client-cancel产生IncompleteRead、BrokenPipe/WinError10058等诊断；这是故障注入结果。相应响应、字节、事件均保存在 '+link(ROOT/'router-results.json')+' 和 '+link(ROOT/'health-supplement-results.json')+'。','4. 初始执行器探测时直接将Get-FileHash作为Bash命令返回127；之后所有Windows原生命令显式经powershell.exe -NoProfile -ExecutionPolicy Bypass执行。该工具调用诊断不记为实现失败。','', '## 本轮时间线、归责与恢复观察','', '- 12:19:59+08首次独立CIM：主38084、辅29624、旧入口10576。G1的旧入口观察是当阶段真实状态。','- 12:27:03+08 G1盘点仍见上述三进程。','- 父级约12:28报告批准安装并空闲重载；本agent未读取其安装/重载证据，也未自行操作生产进程。','- 12:30:35+08独立见新入口44332、创建12:28:14，正式四文件HASH与批准源一致；受限线上GET通过。','- 最终审计再次核对主辅PID/创建时间；所有测试运行目录、高端口、自己的PID清理收据完整。','', '上述只能确证本轮观测和父级通知后的结果。事故发生时间、直接根因、放大因素、责任归属以及更早恢复时间均未独立证实；按隔离要求未读取历史报告。没有把候选代码分析或manifest合同当历史事故证据。','', '## 正式文件身份、合同备份与回退边界','', '以下目标HASH来自本agent实际读取；备份路径与旧HASH仅来自允许的身份合同，**备份未读取/未验证，回退未执行**。','', '| 正式文件 | 当前批准HASH | 合同备份 |','|---|---|---|']
 for f in formal:lines.append('| '+link(pathlib.Path(f['target']))+' | `'+f['targetFinalSha256']+'` | '+(link(pathlib.Path(f['backup'])) if f['backup'] else '新helper，无旧备份')+' |')
 lines+=['', '合同回退方向：在另行授权、核对入口身份与空闲后才可恢复对应旧备份，并复核其originalSha256；不能将旧stub以默认8790重新启动，必须保留网关端口排除。新增共享helper与启动器/guard存在依赖，不能单独移走导致脚本失效。此处只给回退说明，不对可执行回退成功作验收结论。','', '## 两轮状态与残留限制','', '- 第一轮：本agent未读取、未评分、未复核，不在此转述父级结论。','- 第二轮：本报告与score为本agent独立结果。父级仍需把两轮结果按冻结规格合并判断。']
 lines+=['- '+s for s in limitations]
 lines+=['', '## 清理与可复查入口','',f'全部{len(ownedpids)}个已记录测试PID在最终CIM中无本轮残留，{len(ownedports)}个自建高端口不再监听/接受连接；scratch目录为空。只结束本人Popen对象，不按端口批量杀进程。审计器自身在采样时允许存在，并在命令结束后退出。', '', '运行入口：','']
 for n in ('g1_harness.py','live_g2.py','router_harness.py','health_supplement.py','script_harness_v2.py','script_library.ps1','launcher_v3.py','finalize_round2.py'):lines.append('- '+link(ROOT/n))
 lines+=['', '重新运行时不得覆盖本轮结果：这些harness主动拒绝复用既有结果/运行目录。需要另行选择新的自建目录；此报告并未请求再次运行测试。','']
 emit_new('round2-report.md','\n'.join(lines))
 index={'generatedAt':audit['at'],'files':[{'path':str(p),'bytes':p.stat().st_size,'sha256':sha(p)} for p in sorted(ROOT.iterdir()) if p.is_file()],'sourceOnlyAllowedInputs':[str(BASE/'acceptance-spec.md'),str(BASE/'acceptance-spec.lock'),str(BASE/'artifact-manifest.json'),str(BASE/'candidate'), 'C:/temp/stub-rewrite.py','production four approved targets; in-memory router keys; GET whitelist; own round2 artifacts'],'finalSpecRecheck':{'spec':sha(BASE/'acceptance-spec.md'),'lock':sha(BASE/'acceptance-spec.lock')}}
 emit_new('evidence-index.json',json.dumps(index,ensure_ascii=False,indent=2))
 print(json.dumps({'totalScore':total,'verdict':score['verdict'],'vetoesTriggered':[v['id'] for v in vetoes if v['triggered']],'cleanupPassed':cleanup_ok,'ownedPidCount':len(ownedpids),'ownedPortCount':len(ownedports),'specHashMatch':hashes_ok,'formalHashesMatch':all(f['matchesApproved'] for f in formal),'report':str(ROOT/'round2-report.md'),'score':str(ROOT/'score.json')},ensure_ascii=False,indent=2))
 return 0 if passed else 1
if __name__=='__main__':sys.exit(main())
