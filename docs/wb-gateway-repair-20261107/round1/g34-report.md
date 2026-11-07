# 第一轮独立验收：G3/G4

独立结论：**G3 15/15，G4 15/15**。评分仅依据本测试 agent 自建并运行的动态证据；父 agent 未评分，未读取父 agent 或实施 agent 的测试报告。测试写入范围限于 round1，候选与冻结规格未修改。

G3 的最终批次为 24 个业务断言及 4 个清理/冻结断言，28/28 通过。G4 以 89 个库级动态断言、21 个完整原生 guard 业务断言及最后 6 个完整原生缺失/hold 业务断言交叉验证。早期测试夹具失败保留原始记录，不计为候选失败，也不计为通过。

## 固定版本

每阶段均核开始/结束 HASH。最终使用的摘要如下。

| 文件 | SHA-256 |
| --- | --- |
| [acceptance-spec.md](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/acceptance-spec.md>) | c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91 |
| [acceptance-spec.lock](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/acceptance-spec.lock>) | 50f75118dffa481cd7299f17c6e2981b7dcc96252261cc1543382428bf0fe88b |
| [start-silent.cmd](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/candidate/start-silent.cmd>) | 10b4065059c4cc0b35a30d4a1875285bc505ac23066e5a9fc5e3ac40333604e7 |
| [wb-service-check.ps1](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/candidate/wb-service-check.ps1>) | 66091dea24cad91aa8b96c95de90857c22d3c0e98399b311d2ca72362f8ae512 |
| [wb-gateway-guard.ps1](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/candidate/wb-gateway-guard.ps1>) | 5d7e6cb35a4a60f98ec72a6ba006690bbb93c40a2f97e34abc802331dd43d1c1 |
| [wb-router.js](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/candidate/wb-router.js>) | 2262451f13e4dcef160bb133d1fd17754aa56f0a4ace45c069ea0ebf59c7ce0f |

## 隔离与适配边界

假主/辅服务为本 agent 编写、编译的独立 C# TCP 服务，入口身份夹具为 Node HTTP 服务。所有端口先分配高位空闲端口，夹具与适配器均拒绝 8788/8789/8790/6199；没有为测试停止任何真实主池、辅池或入口。原生 PowerShell、Get-NetTCPConnection、Win32_Process/CIM、HTTP 与进程身份检查实际运行，假池仅有自造配置和本地测试数据。

G3 使用系统 PowerShell 执行隔离 CMD。冻结 CMD 仅将硬编码生产 sanitizer 调用的一行改向本地 argv/退出码记录器，避免写生产日志；其余 CMD 字节反向还原后与候选完全相等。helper 路径处使用独立 CLI 适配器，导入候选 helper 的原样副本，只将服务定义端口映射至自建高端口，保留实际身份判断、健康验证、等待函数和退出码。精确原行、替换行、两种 HASH、helper 原样 HASH 与 adapter HASH 均在最终 G3 证据的 transformations 中。未在真实 run 目录调用启动器。

G4 运行冻结 guard 原文件，通过 HelperPath 注入端口定义/等待参数记录适配器，再导入候选 helper 原字节。业务状态判别、原生盘点、HTTP、配置消毒、hold 判断和启动决策均来自候选。

## G3：15/15

| 必须条目 | 独立动态结果 |
| --- | --- |
| 主进程路径与健康身份均正确 | HTTP 健康检查与实际 exe 身份成功；CMD 退出 0 |
| 健康时幂等 | 连续两次 CMD 退出 0；主/入口启动收据数不增加 |
| 外来进程占用主端口 | CMD 退出 20，明确 CONFLICT；原假进程保持存活，没有新增服务 |
| 正确进程暂不健康 | CMD 退出 30，state=unhealthy；没有重复启动或误杀 |

附加动态覆盖：入口 foreign 同样退出 20；入口缺失时真实 WScript 启动一次，helper 调用保留 WaitSeconds=20；主缺失时前台假 exe 启动一次、保留 -config 与 panel.log、传播退出码 0；hold 返回 10；sanitizer 模拟退出 1 可继续，退出 2 则 CMD 返回 40。

最终 [G3 原始证据](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/g3-cmd-system-evidence-v2.json>)：28/28 通过，27 个场景端口全部关闭，持有的子进程句柄全部退出，临时 runtime 已删除，开始/结束冻结摘要一致。

## G4：15/15

| 必须条目 | 动态证据 |
| --- | --- |
| 覆盖主、辅、入口三服务 | 库定义检查与完整原生 guard 实际分别执行三类服务 |
| 身份正确且健康 | 三服务健康，guard 退出 0；重复执行不新增服务 |
| 正确进程暂不健康 | 主、辅、入口分别执行，均退出 30，明确 WARN UNHEALTHY；启动数不变，原进程保持存活 |
| 外来占用 | 三服务分别执行，均退出 20、明确 CONFLICT foreign；没有误报正常，没有终止外来假进程 |
| 端口缺失 | 最后完整 guard 批次在三个端口全部缺失时逐项报告 MISSING；NoRestart 退出 10，三项均 NO_RESTART，零启动 |
| 保留 .hold-restart | 两个服务目录均放置测试 hold；三个端口缺失，完整 guard 三项均 HOLD、退出 10、零启动 |
| 保留配置消毒 | 直接运行候选修复函数：正确 BOM JSON 去 BOM，保存逐字节原始快照并更新有效备份；损坏 JSON 保留原始文件和上次有效备份；非对象 JSON 拒绝 |
| 健康失败不无条件杀重启 | 三服务不健康与 foreign 原生场景均不启动、不杀进程；库级测试补齐异常/检查错误/启动竞态 |

89 个库断言还实测同 PID 创建时间变更被拒绝、foreign 不发 HTTP、missing→foreign 竞态不启动、错误配置禁止恢复、watcher 参数精确身份与 eval 伪装拒绝。开始/结束摘要保持一致。

完整 guard 发生真实 missing 恢复尝试时，等待适配器记录 `Name=main, Seconds=2`。这直接证明完整 dot-source 链未把传入恢复等待秒数覆盖为 helper 的 0。该尝试最终明确返回 missing / recovery-failed / 10，没有谎报恢复成功。

必要的限制：**自编 C# 假服务经 WScript 的成功拉起集成未完成**。独立 cscript 直接运行同一自建 VBS 也在 8 秒超时，不经过 guard；该测试边界不能归责候选。两个尝试的自建 WScript 38440、33156 均按完整 exe/script 路径、父 PID 重新核实后清理，相关 runtime 已删除。成功恢复只在库级注入启动/等待回调中动态覆盖；未将库级结果冒充原生 WScript 成功恢复。G4 冻结条目要求的 missing 状态区分、明确失败、hold、无误杀已由完整原生调用证实，因此不以这项额外成功恢复检查补分或扣分。

## 正式命令与证据

原始命令完整 argv、cwd、输出、退出码保存在对应 JSON；下列均以共享工作区为 cwd。

```text
python docs/wb-gateway-repair-20261107/round1/g3-cmd-system-powershell.py
python docs/wb-gateway-repair-20261107/round1/g34-guard-native-only.py
python docs/wb-gateway-repair-20261107/round1/g34-vbs-launch-diagnostic.py
```

历史批次的文件名带版本，以其原始 commands 为准。正式测试曾在隔离夹具参数、编码、VBS、清理边界处失败；没有隐去或以最终成功覆盖原始证据。

- [库级独立证据：89/89](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/g34-library-evidence-v4.json>)
- [原生 guard 21 个业务断言；含后续夹具失败原记录](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/g34-guard-native-evidence-v5.json>)
- [最后原生 guard 批次：10/10，含 6 个业务断言](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/g34-guard-native-evidence-v7.json>)
- [完整 guard 等待参数原始收据](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/g34-v6-guard-main-missing-recover-wait-calls.jsonl>)
- [独立 VBS 诊断、8 秒超时及清理](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/g34-vbs-launch-diagnostic-evidence.json>)
- [自建 WScript 38440 清理证据](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/g34-v5-owned-wscript-cleanup.json>)
- [自建 WScript 33156 清理证据](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/g34-v6-owned-wscript-cleanup.json>)

生产入口是否已经部署不由本 G3/G4 分数推断；完整第一轮结论还需最后线上 GET、冻结摘要、真实池身份及全量自建进程清理复核。
