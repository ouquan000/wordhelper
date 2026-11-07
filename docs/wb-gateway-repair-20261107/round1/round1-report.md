# WB 修复第一轮独立验收结果

**第一轮评分：100/100；G1–G6 均通过，K1–K6 均未触发。** 本结论针对下列固定候选与本轮独立线上 GET / 隔离动态测试，包含明确测试边界；不表示候选已部署，也不代替冻结规格要求的第二轮独立复核。父 agent 未参与评分。

执行窗口：2026-11-07 11:29–12:12（UTC+08）。正式评分见 [round1-score.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/round1-score.json>)。

## 逐项评分

| 项目 | 独立得分 | 核心证据与结果 |
| --- | --- | --- |
| G1 迁移 | 15/15 | 实际 8790 owner 为正确主 exe，健康身份正确；原 stub 默认 18791、显式 18792 独立运行，只 GET；三个网关端口 argv 全部拒绝，未实际 bind；零队列写入 |
| G2 主链路 | 15/15 | 主与入口 healthz、鉴权 models、入口 panel 正常；模型 ID 集合排序后相等；辅 PID 29624 与 healthy=1 保持；结束时再次独立复核 |
| G3 启动身份 | 15/15 | 最终 28/28 断言通过；主健康重复幂等；外来占用返回 20 / CONFLICT；正确进程不健康返回 30，不重复启动、不误杀 |
| G4 守护 | 15/15 | 89 个库断言、27 个完整原生 guard 业务断言覆盖三服务健康/不健康/外来/缺失/NoRestart/hold；BOM 消毒、有效备份与损坏配置保护动态验证；健康失败无无条件杀重启 |
| G5 健康路由 | 25/25 | 52 个动态用例；16 格路由矩阵；两池各 15 种坏健康输入；过期/进行中探测；真实 netstat QQ 状态转换；5:3、在线/暂停禁借辅池、坏主 main-only 路径明确失败 |
| G6 不重放/流式 | 15/15 | 17 个动态用例；空/非空 POST 的 404/429/reset 不重放；SSE 原字节、首包、取消和持续活跃超时验证；107 唯一请求 = 107 接收记录，重复 0；测试清理完成 |
| **合计** | **100/100** | **仅第一轮** |

对应独立阶段报告：[G1/G2](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/stage12-report.md>)、[G3/G4](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/g34-report.md>)、[G5/G6](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/g56-report.md>)。早期阶段报告中的“未测”是当时状态，以本汇总与最终证据为准。

## 否决项

| 否决项 | 第一轮结果 | 独立依据 |
| --- | --- | --- |
| K1 改规格/锁 | 未触发 | 每阶段开始/结束与最终 HASH 一致 |
| K2 停真实池/改凭证数据模型/真实推理 | 未触发 | 本 agent 线上仅 allowlisted GET；零真实 POST、零生产 stop；不读取 auths，不改账号/Clash/proxy/模型/额度/学习数据 |
| K3 自动杀外来进程 | 未触发 | 启动器与三类 guard foreign 原生场景均明确冲突、非零、无新增服务；外来假进程保留至本 agent 自建夹具清理；没有按端口杀进程 |
| K4 在线/暂停抢用辅池 | 未触发 | 完整矩阵与实际隔离 QQ TCP/netstat 变化中，辅池接收始终符合条件 |
| K5 已发送请求重放 | 未触发 | 404/429/三类 reset × 两种请求体；107 次实际接收无重复 ID |
| K6 留测试进程/污染真实数据 | 未触发 | 结束原生扫描自建 fixture 为 0；所有临时 runtime 删除；各批次端口关闭；背景作业全部结束并收取结果；零学习数据/队列写入 |

## 最后线上独立复核

2026-11-07 12:12:06.015–12:12:19.608（UTC+08），[final-readonly-evidence.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/final-readonly-evidence.json>) **13/13 通过**。

| 端口 | 结束 PID / 创建时间 | GET 观测 |
| --- | --- | --- |
| 主 8790 | 38084；2026-11-07 11:10:53.387778+08 | healthz 200，workbuddy2api，healthy=7；鉴权 models 200，75 个现场 ID |
| 辅 8789 | 29624；2026-11-06 16:57:05.165316+08 | healthz 200，workbuddy2api，healthy=1；PID/exe/创建时间/命令行与本轮最初观测一致 |
| 入口 8788 | 10576；2026-11-06 16:54:20.755652+08 | healthz 200 / healthy=7；鉴权 models 200，ID 集合与主池相等；panel 200；router status 200 |

75 为现场观测值，不作为唯一固定模型数量标准。既有本地 key 仅在内存读入并用于 HTTP 请求头，没有写入报告或证据；未读取账号凭证库。主池 PID/exe/创建时间/命令行也与本轮最初观测一致。生产 router 配置在读取窗口前后 SHA 相同。正式 helper 文件被独立观测为存在且 HASH 与候选相同；未据此推断其余候选已部署。入口仍为原 PID 10576。

## 冻结版本

规格开始与最终 SHA-256 均为 `c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91`；锁文件均为 `50f75118dffa481cd7299f17c6e2981b7dcc96252261cc1543382428bf0fe88b`。最终四候选 HASH 与父 agent ready 时独立确认的值完全一致。

| 候选 | SHA-256 |
| --- | --- |
| [wb-router.js](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/candidate/wb-router.js>) | 2262451f13e4dcef160bb133d1fd17754aa56f0a4ace45c069ea0ebf59c7ce0f |
| [start-silent.cmd](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/candidate/start-silent.cmd>) | 10b4065059c4cc0b35a30d4a1875285bc505ac23066e5a9fc5e3ac40333604e7 |
| [wb-service-check.ps1](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/candidate/wb-service-check.ps1>) | 66091dea24cad91aa8b96c95de90857c22d3c0e98399b311d2ca72362f8ae512 |
| [wb-gateway-guard.ps1](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/candidate/wb-gateway-guard.ps1>) | 5d7e6cb35a4a60f98ec72a6ba006690bbb93c40a2f97e34abc802331dd43d1c1 |

## 测试边界与保留的失败

G3 隔离 CMD 的一行硬编码生产 sanitizer 调用被重定向到本地记录器，原行/替换行/双方 HASH/逐字节还原断言均有证据。helper 适配器导入候选原字节，仅映射高端口。该评分没有在生产路径执行启动器或 sanitizer。G4 使用完整候选 guard 与原 helper，外加高端口定义/等待收据适配器。

G4 **自编 C# 假服务经 VBS 成功恢复的额外原生集成尚未建立**；直接 cscript 执行同一自建 VBS、不经过候选 guard 也超时，因此没有归为候选故障。完整 guard 已动态验证三服务 missing 明确退出 10、NoRestart、hold；实际恢复尝试明确报告 recovery-failed，未谎报健康。完整 dot-source 后原始等待收据为 Seconds=2，参数没有被 helper 的默认 0 覆盖。库级成功恢复/竞态断言不冒充原生 VBS 成功恢复。此限制不改变冻结 G4 关于状态区分与保护的逐字条目。

原始失败批次、诊断输出、两个自建 WScript 按完整身份重新核实后的清理收据均保留在 round1。最终无残留进程或临时目录。没有通过删除失败证据、修改候选或改变评分规格取得分数。

第二轮独立 agent 尚未由本 agent执行；本报告不作第二轮结论，也不作部署完成声明。事故全局时间线、历史责任、生产备份与回退实施属于父 agent 的最终事故/交付报告，本轮不采用其未经独立验证的历史叙述作为评分依据。
