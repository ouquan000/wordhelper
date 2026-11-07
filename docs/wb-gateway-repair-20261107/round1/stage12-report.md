# 第一轮独立验收：G1/G2 阶段结果

测试窗口：2026-11-07 11:29:13–11:29:28（UTC+08）。执行者为独立测试 agent；父 agent 不评分。本阶段没有读取父 agent 的 smoke/live evidence，也没有读取或轮询 candidate。

**阶段结论：G1 15/15，G2 15/15；已测项合计 30/30。完整 100 分验收尚未完成，剩余 70 分未测。**

## 冻结规格

开始与结束均独立计算 SHA-256：

| 文件 | 开始 / 结束 SHA-256 | 结果 |
| --- | --- | --- |
| [acceptance-spec.md](D:/deepseek%20harness/work_anki_enligish/docs/wb-gateway-repair-20261107/acceptance-spec.md) | `c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91` | 一致，符合指定值 |
| [acceptance-spec.lock](D:/deepseek%20harness/work_anki_enligish/docs/wb-gateway-repair-20261107/acceptance-spec.lock) | `50f75118dffa481cd7299f17c6e2981b7dcc96252261cc1543382428bf0fe88b` | 一致，锁内指定规格摘要正确 |

仅新增 round1 下正式测试、报告和必要证据。没有改实现、规格、锁文件或生产配置。原 router.config.json 的独立前后摘要均为 `21216097818995b03baf7945bb4ff0507422ae1c8275e5bc4f9a0b38692f1cd2`；原 stub 的独立前后摘要均为 `4bcf738c9bec23d3cae3c518b700c2af567a718733411670398bb908f17b7d24`。

## G1：15/15，通过

- 8790 监听 PID 38084，实际 exe 为 `D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/wb2api.exe`；GET /healthz 为 HTTP 200、`service=workbuddy2api`。
- 对真实 stub 的 `--port 8788`、`--port 8789`、`--port 8790` 分别运行真实 argv/argparse 校验。隔离 shim 在执行前替换 `http.server.ThreadingHTTPServer`；如进入 bind 阶段会输出 `BIND_GUARD_REACHED` 并退出 91，绝不实际 bind 网关端口。三次均退出 2，错误为 `8788, 8789 and 8790 are reserved WB gateway ports`，未触及构造器。
- 先用监听快照和独占 bind/close 预检确认端口空闲，再无参数启动原 stub。stdout 确认为 `127.0.0.1:18791`，GET /rewrite/status 两次均 HTTP 200，`queued=0, done=0, failed=0`。
- 显式 `--host 127.0.0.1 --port 18792` 以同样预检和两次 GET 验证通过。
- 自建 stub PID 14552、11996 仅由持有自身进程句柄的 Popen 对象终止并 wait 回收。Windows 终止后的 exit 1 是主动清理结果；没有按端口查杀或杀旧 PID。清理后两测试端口均不再监听。
- 全阶段没有 POST，没有写真实或测试 App 队列，没有创建学习数据。

## G2：15/15，通过

| 检查 | 独立观测 | 结果 |
| --- | --- | --- |
| 8790 /healthz | 200；workbuddy2api；healthy=7、total=7 | 通过 |
| 8788 /healthz | 200；workbuddy2api；healthy=7、total=7 | 通过 |
| 8789 /healthz | 200；workbuddy2api；healthy=1、total=1 | 通过 |
| 8790 /v1/models | 既有本地 main.key 鉴权；200；75 个有效 ID | 通过 |
| 8788 /v1/models | 同一本地 key 鉴权；200；75 个有效 ID | 通过 |
| 模型目录集合 | `sorted(set(main_ids)) == sorted(set(entry_ids))`，差集为空 | 通过 |
| 8788 /panel/ | 200；text/html；109357 bytes | 通过 |
| 8788 /_router/status | 200，旧入口仍在运行；仅留字段名和响应摘要 | 观测证据 |

75 是此次现场数量，不是唯一固定验收标准。API key 仅从原 router.config.json 的 main.key 读入内存用于请求头，没有输出或写入证据；未读取 auths。健康 JSON、排序后的模型 ID、HTTP 元数据与原始命令均保留于正式证据，面板仅保存标题/大小/摘要，不保存正文。

前后监听身份一致：

| 端口 | PID | 实际路径 | 创建时间（UTC+08） |
| --- | --- | --- | --- |
| 8790 | 38084 | `D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/wb2api.exe` | 2026-11-07 11:10:53.387778 |
| 8789 | 29624 | `D:/deepseek_v4.1flash1017/workbuddy2api-panel/run-bot/wb2api.exe` | 2026-11-06 16:57:05.165316 |
| 8788 | 10576 | `C:/Program Files/nodejs/node.exe`，命令行仍为原 run/wb-router.js | 2026-11-06 16:54:20.755652 |

三个监听的 PID、exe、创建时间、命令行前后均相同；没有停止真实主池、辅池或入口。

## 尚未完成的评分

| 项目 | 满分 | 阶段状态 |
| --- | --- | --- |
| G1 迁移 | 15 | 15，通过 |
| G2 主链路恢复 | 15 | 15，通过 |
| G3 启动身份检查 | 15 | 未测，等待 candidate ready |
| G4 守护 | 15 | 未测，等待 candidate ready |
| G5 主辅健康路由 | 25 | 未测，等待 candidate ready |
| G6 不重放与流式 | 15 | 未测，等待 candidate ready |

| 否决项 | 当前可证明内容 | 完整结论 |
| --- | --- | --- |
| K1 改规格/锁 | 开始/结束双 HASH 一致 | 本阶段未触发，完整测试结束再核 |
| K2 停真实池/改数据凭证模型/真实 inference | 本阶段只有 allowlisted 线上 GET，零生产 stop、零 POST | 本阶段未触发，完整测试持续约束 |
| K3 自动杀外来占用 | 本阶段只回收自建 stub；候选冲突行为未测试 | 未测候选 |
| K4 QQ 在线/辅池暂停时抢用辅池 | 未发送隔离推理请求 | 未测 |
| K5 已发送请求重放 | 未发送隔离推理请求 | 未测 |
| K6 测试进程/真实数据污染 | 两个自建 stub 已回收，18791/18792 无监听；零队列写入 | 本阶段未触发，完整测试结束再核 |

不能用源码推测补齐这些未测项；本阶段没有完整修复完成结论。父 agent 随后 send_message 通知候选 ready 后，继续对指定四个候选文件进行 G3–G6 隔离动态测试，最终逐项独立评分 G1–G6、K1–K6。

## 正式测试和证据

入口命令（工作目录为共享工作区）：

```text
python docs/wb-gateway-repair-20261107/round1/stage12_acceptance.py
```

此命令真实执行返回 exit 0；27 个检查全部通过，其中 G1 10 个、G2 11 个，其余为冻结/只读/清理检查。证据内保留 PowerShell 监听身份快照的完整 argv、三次安全 argv 校验的完整 argv/退出码/stderr、两次 stub 启动 argv 与每次 GET 响应、进程清理结果、双 HASH。

- [stage12_acceptance.py](D:/deepseek%20harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/stage12_acceptance.py)：独立正式测试，输出日志采用独占创建，防止误覆盖已有证据。
- [stage12-evidence.json](D:/deepseek%20harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/stage12-evidence.json)：结构化原始命令与关键输出。
- [stub-default-stdout.log](D:/deepseek%20harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/stub-default-stdout.log)：默认端口启动及两次 GET 的 stdout。
- [stub-explicit-stdout.log](D:/deepseek%20harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/stub-explicit-stdout.log)：显式端口启动及两次 GET 的 stdout。

事故时间线、归责、改动备份与回退方案属于整体故障报告；本阶段没有事故原始证据与候选实现，不对这些内容作推断。
