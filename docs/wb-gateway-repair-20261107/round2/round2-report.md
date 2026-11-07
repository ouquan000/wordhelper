# WB 网关修复第二轮独立验收报告

本轮得分 **100/100**；K1–K6 在已测范围内均未触发。结论：**本轮通过，附明确测试边界**。本报告仅包含本人新建harness与独立实测；第一轮及历史事故结论未读取、未代证。

结束审计时间：2026-11-07T04:39:24.118092+00:00。完整逐G/K原始命令与结果见 [score.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/score.json>)，最终清理/进程/HASH收据见 [final-audit.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/final-audit.json>)。

## 冻结与独立性

- 规格：[acceptance-spec.md](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/acceptance-spec.md>)，SHA-256 `c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91`。
- 锁文件：[acceptance-spec.lock](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/acceptance-spec.lock>)，SHA-256 `50f75118dffa481cd7299f17c6e2981b7dcc96252261cc1543382428bf0fe88b`。
- 身份合同：[artifact-manifest.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/artifact-manifest.json>)，SHA-256 `1e1583a1137454a88ca0b387c5be212279f61c4d9c19db3340c7ed89a4bda564`；仅作为批准身份及回退路径，不作为部署或实测通过证据。
- 开始、G1结束、最终线上GET后及最终清理后均复核规格/锁；无实现、评分权重、否决项修改。
- 所有脚本、假包、日志、结果仅创建于本round2目录；隔离副本变换逐项记源/副本HASH，全部生产网络调用严格限制为GET白名单。

## G项评分与证据

| 项 | 分数 | 实测结论 | 原始结果 |
|---|---:|---|---|
| G1 | 15/15 | 8790真实WB身份；原样stub默认18791、显式高端口与三保留端口拒绝通过。 | [g1-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/g1-results.json>) |
| G2 | 15/15 | 入口44332与主38084/辅29624身份自行CIM核对；健康、鉴权模型目录、panel和status GET通过，正式文件匹配批准源。 | [live-g2-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/live-g2-results.json>) |
| G3 | 15/15 | 有效原生CMD健康0/正确但不健康30/外来冲突20/hold缺失10；未重复启动或误杀。 | [launcher-v3-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/launcher-v3-results.json>) |
| G4 | 15/15 | 原生CLI盘点和库级隔离矩阵组合：三服务四状态、显式冲突、不健康不启动、hold、race、BOM/坏JSON备份规则通过。 | [script-v2-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/script-v2-results.json>) |
| G5 | 25/25 | 两池22项新鲜坏健康探测、唯一健康池、80请求50:30、online/paused主坏503、主专属GET及状态可见性通过。 | [health-supplement-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/health-supplement-results.json>) |
| G6 | 15/15 | 404/429/reset/partial两池各只转发一次；SSE逐字节及时到达、拆分UTF8保持、取消向上游传播；全体测试清理通过。 | [router-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/router-results.json>) |

### G1：迁移与保留端口

先确认18791空闲后启动原样stub副本，GET `/rewrite/status`、`/models`、`/rewrite/results` 均200；显式端口64506成功；三保留端口8788/8789/8790均在参数阶段退出2并明确拒绝。未POST、未写真实App队列。主8790实测服务身份为workbuddy2api，CIM路径属于正式run目录WB程序。

### G2：正式安装后的独立线上观察

12:30:35–12:30:48+08独立GET/CIM观察：入口PID44332，创建时间2026-11-07 12:28:14；主PID38084创建11:10:53，辅PID29624创建2026-11-06 16:57:05，最终清理审计时主辅身份仍与本次线上起点完全相同。

| GET | 观察 |
|---|---|
| 8790、8788 `/healthz` | HTTP200，service=workbuddy2api，healthy=7 |
| 8789 `/healthz` | HTTP200，service=workbuddy2api，healthy=1 |
| 8790、8788 `/v1/models` | 携带仅内存使用的本地鉴权；当前排序去重ID集合完全相等，未硬编码数量 |
| 8789 `/v1/models` | HTTP200，独立记录当前目录；未混入入口主专属目录 |
| 8790、8788 `/panel/` | HTTP200、HTML；同长度109357且body SHA一致 |
| 8788 `/_router/status` | 双池健康与身份可见，权重5:3、迟滞180000/300000 |
| 三端 `/status` | 鉴权HTTP200 JSON；只记长度/HASH，不保存响应明文 |

上述面板检查属于HTTP可用性，未执行浏览器交互。账号配置不在本轮读取范围，未声称做了部署前后账号配置内容审计。

### G3/G4：原生证据和库级证据的边界

启动器的有效CMD调用使用自建包目录为cwd、裸脚本名。健康时两服务检查通过且starts文件前后相同；正确主进程健康数0时退出30并保持进程；外来Python监听即使返回合法WB健康JSON仍因exe路径不符报CONFLICT、退出20且监听继续；missing+.hold-restart退出10。

G4原生CLI在三个高端口使用真实Get-NetTCPConnection+CIM+HTTP，覆盖三服务健康、主辅暂坏、main外来占用、main缺失NoRestart。原生healthy整次返回10来自故意缺失的自建watcher，三个服务行均为healthy/ExitCode0；没有把整次调用说成exit0。

三服务×healthy/unhealthy/missing/foreign、hold、NoRestart、坏配置、缺失后出现foreign的二次检查，用候选定义和候选函数做库级隔离回调。missing时Start/Wait各一次并回到模拟健康；foreign/unhealthy时Start零次。**没有原生VBS缺失恢复或生产watcher恢复成功证据。**

BOM测试在自建合成JSON上实际执行候选配置修复函数，验证去BOM与备份；坏JSON原字节及最后有效备份保持。launcher仅把外部sanitizer调用定向为自身记录器，保留原控制流；生产sanitizer实现未测。

所有高端口字面替换、sanitizer路径、日志路径、CRLF变换与源/副本HASH见对应 `isolation_*` 原始记录。router候选副本保持逐字节一致。

### G5/G6：隔离POST、故障与SSE

80次合成推理覆盖10个路由，50次主、30次辅。另对两池逐项切换wrong service、invalid JSON、404、0、负数、小数、字符串、布尔、缺字段、超限body、timeout；每项都要求checkedAt晚于变更时间且reason匹配，随后新POST只到另一个健康池。双坏、online/paused且主坏均503且无上游POST。主专属health/models/panel/status在主坏时明确503，健康时只主。

QQ使用本轮编译的假netstat进程注入ESTABLISHED行，验证offline→online及online→offline，不读取或操控真实QQ。隔离配置加速探测/迟滞；候选源和生产迟滞未变。

SSE收到6块，首块约15.0ms，末块约625.0ms。逐字节完整hex与期望一致，包含跨块拆分的UTF8字符。客户端取消到假上游确认约156.0ms；上传只送64/声明50000字节后断开，假上游观察到取消且仅一次分发。

404、429、连接reset、部分响应截断在主/辅各测一次，全部POST_BEGIN恰好一次，未跨池重放。

## K项否决核对

| 项 | 结论 | 自己的命令/原始证据 |
|---|---|---|
| K1 | 已测范围内未触发；规格/锁起止HASH与指定值一致，无权重/否决项变动。 | [g1-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/g1-results.json>)；每条命令与raw收录于[score.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/score.json>) |
| K2 | 已测范围内未触发；全部生产网络调用只GET白名单路径；POST只去隔离高端口；真实主辅PID/创建时间保持；无生产写入或真实推理。 | [live-g2-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/live-g2-results.json>)；每条命令与raw收录于[score.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/score.json>) |
| K3 | 已测范围内未触发；外来占用在原生G3/G4测试后仍监听；库级foreign及race分支零启动，不存在按端口批量kill。 | [launcher-v3-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/launcher-v3-results.json>)；每条命令与raw收录于[score.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/score.json>) |
| K4 | 已测范围内未触发；QQ在线或辅暂停时所有新推理只主；主坏503且无上游POST。 | [router-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/router-results.json>)；每条命令与raw收录于[score.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/score.json>) |
| K5 | 已测范围内未触发；两池各404/429/reset/partial后的POST_BEGIN均恰好一次，没有跨池重放。 | [health-supplement-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/health-supplement-results.json>)；每条命令与raw收录于[score.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/score.json>) |
| K6 | 已测范围内未触发；所有已记录自建PID结束、高端口闭合、scratch目录删除；未触8765/手机/模拟器/学习数据。 | [final-audit.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/final-audit.json>)；每条命令与raw收录于[score.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/score.json>) |

## 失败诊断与保留情况

1. 首次native harness给单次CMD调用20s预算，发生TimeoutExpired；该次不作为候选失败或通过。完整异常和清理原始记录保留在 [script-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/script-results.json>) 与 [script-evidence.jsonl](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/script-evidence.jsonl>)。
2. 第二次harness把带引号路径作为CMD argv元素，Windows收到字面转义引号，四个启动器case返回shell错误；候选未执行。原失败stdout/stderr永久保留在 [script-v2-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/script-v2-results.json>)。改用cwd+裸脚本名的有效调用后，[launcher-v3-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/launcher-v3-results.json>) 四项通过。未删除原失败记录，也未修改候选。
3. 故意reset/partial/client-cancel产生IncompleteRead、BrokenPipe/WinError10058等诊断；这是故障注入结果。相应响应、字节、事件均保存在 [router-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/router-results.json>) 和 [health-supplement-results.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/health-supplement-results.json>)。
4. 初始执行器探测时直接将Get-FileHash作为Bash命令返回127；之后所有Windows原生命令显式经powershell.exe -NoProfile -ExecutionPolicy Bypass执行。该工具调用诊断不记为实现失败。

## 本轮时间线、归责与恢复观察

- 12:19:59+08首次独立CIM：主38084、辅29624、旧入口10576。G1的旧入口观察是当阶段真实状态。
- 12:27:03+08 G1盘点仍见上述三进程。
- 父级约12:28报告批准安装并空闲重载；本agent未读取其安装/重载证据，也未自行操作生产进程。
- 12:30:35+08独立见新入口44332、创建12:28:14，正式四文件HASH与批准源一致；受限线上GET通过。
- 最终审计再次核对主辅PID/创建时间；所有测试运行目录、高端口、自己的PID清理收据完整。

上述只能确证本轮观测和父级通知后的结果。事故发生时间、直接根因、放大因素、责任归属以及更早恢复时间均未独立证实；按隔离要求未读取历史报告。没有把候选代码分析或manifest合同当历史事故证据。

## 正式文件身份、合同备份与回退边界

以下目标HASH来自本agent实际读取；备份路径与旧HASH仅来自允许的身份合同，**备份未读取/未验证，回退未执行**。

| 正式文件 | 当前批准HASH | 合同备份 |
|---|---|---|
| [wb-router.js](<D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/wb-router.js>) | `2262451f13e4dcef160bb133d1fd17754aa56f0a4ace45c069ea0ebf59c7ce0f` | [wb-router.js.bak-wb-repair-20261107-1105](<D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/wb-router.js.bak-wb-repair-20261107-1105>) |
| [start-silent.cmd](<D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/start-silent.cmd>) | `10b4065059c4cc0b35a30d4a1875285bc505ac23066e5a9fc5e3ac40333604e7` | [start-silent.cmd.bak-wb-repair-20261107-1105](<D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/start-silent.cmd.bak-wb-repair-20261107-1105>) |
| [wb-service-check.ps1](<D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/wb-service-check.ps1>) | `66091dea24cad91aa8b96c95de90857c22d3c0e98399b311d2ca72362f8ae512` | 新helper，无旧备份 |
| [wb-gateway-guard.ps1](<D:/deepseek_v4.1flash1017/thebigfish/tools/wb-gateway-guard.ps1>) | `5d7e6cb35a4a60f98ec72a6ba006690bbb93c40a2f97e34abc802331dd43d1c1` | [wb-gateway-guard.ps1.bak-wb-repair-20261107-1105](<D:/deepseek_v4.1flash1017/thebigfish/tools/wb-gateway-guard.ps1.bak-wb-repair-20261107-1105>) |
| [stub-rewrite.py](<C:/temp/stub-rewrite.py>) | `4bcf738c9bec23d3cae3c518b700c2af567a718733411670398bb908f17b7d24` | [stub-rewrite.py.bak-wb-repair-20261107-1105](<C:/temp/stub-rewrite.py.bak-wb-repair-20261107-1105>) |

合同回退方向：在另行授权、核对入口身份与空闲后才可恢复对应旧备份，并复核其originalSha256；不能将旧stub以默认8790重新启动，必须保留网关端口排除。新增共享helper与启动器/guard存在依赖，不能单独移走导致脚本失效。此处只给回退说明，不对可执行回退成功作验收结论。

## 两轮状态与残留限制

- 第一轮：本agent未读取、未评分、未复核，不在此转述父级结论。
- 第二轮：本报告与score为本agent独立结果。父级仍需把两轮结果按冻结规格合并判断。
- 未读、未复用第一轮任何测试/报告/证据；第一轮结果未独立核验。
- 未读实施者测试、notes、父级事故报告；历史事故时间线、直接根因、放大因素与责任归属未验证。
- 原生VBS缺失服务启动、生产watcher启动恢复未测；G4恢复路径仅库级回调。
- 真实QQ、手机、模拟器、8765、真实学习队列、真实模型请求均未测。
- 回退仅按身份合同描述，未执行、未读取或验证旧备份内容。
- 本报告只能证明本轮测试边界内的观察，不声明两轮总体验收或历史配置审计完成。

## 清理与可复查入口

全部18个已记录测试PID在最终CIM中无本轮残留，21个自建高端口不再监听/接受连接；scratch目录为空。只结束本人Popen对象，不按端口批量杀进程。审计器自身在采样时允许存在，并在命令结束后退出。

运行入口：

- [g1_harness.py](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/g1_harness.py>)
- [live_g2.py](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/live_g2.py>)
- [router_harness.py](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/router_harness.py>)
- [health_supplement.py](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/health_supplement.py>)
- [script_harness_v2.py](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/script_harness_v2.py>)
- [script_library.ps1](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/script_library.ps1>)
- [launcher_v3.py](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/launcher_v3.py>)
- [finalize_round2.py](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round2/finalize_round2.py>)

重新运行时不得覆盖本轮结果：这些harness主动拒绝复用既有结果/运行目录。需要另行选择新的自建目录；此报告并未请求再次运行测试。
