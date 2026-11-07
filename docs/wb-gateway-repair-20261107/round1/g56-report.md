# 第一轮独立验收：G5/G6 路由候选阶段

测试时间：2026-11-07 11:37:09.717–11:37:32.979（UTC+08）。本测试由独立测试 agent 自行编写并运行，没有读取实施 agent 的测试、父 agent 的 smoke/live evidence 或第一方评分。

**G5 25/25，通过；G6 15/15，通过。此次动态用例 69/69 通过。连同此前 G1/G2，目前已测项目为 70/70 分；总分 100 中的 G3/G4 共 30 分仍未测，不能报告完整验收完成。**

## 版本与冻结校验

开始与结束均计算 SHA-256，三项完全一致：

| 目标 | 开始与结束 SHA-256 |
| --- | --- |
| [acceptance-spec.md](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/acceptance-spec.md>) | `c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91` |
| [acceptance-spec.lock](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/acceptance-spec.lock>) | `50f75118dffa481cd7299f17c6e2981b7dcc96252261cc1543382428bf0fe88b` |
| [candidate/wb-router.js](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/candidate/wb-router.js>) | `2262451f13e4dcef160bb133d1fd17754aa56f0a4ace45c069ea0ebf59c7ce0f` |

运行的是放在 round1 临时目录中的候选字节原样副本，每个副本的 HASH 也独立核对。候选内容没有修改。仅加载一个独立的生命周期测试 hook，在清理阶段通过 IPC 触发候选自身 SIGTERM handler 后断开 IPC；hook 不修改路由、健康、转发或 QQ 探测逻辑。

## G5：52 用例，25/25

完整路由矩阵共有 16 格：QQ 的 online/offline × 辅池暂停/未暂停 × 主池健康/不健康 × 辅池健康/不健康。每格发送 8 次独立假 POST，交替空请求体和非空请求体。断言以两假池实际接收记录为准，未从源码推测结果。

| QQ / pause 条件 | 主池好、辅池好 | 主池好、辅池坏 | 主池坏、辅池好 | 两池坏 |
| --- | --- | --- | --- | --- |
| offline / 未暂停 | 主 5、辅 3 | 全主 | 全辅 | 503，零上游接收 |
| offline / 已暂停 | 全主 | 全主 | 503，零上游接收 | 503，零上游接收 |
| online / 未暂停 | 全主 | 全主 | 503，零上游接收 | 503，零上游接收 |
| online / 已暂停 | 全主 | 全主 | 503，零上游接收 | 503，零上游接收 |

两池分别验证以下 15 种异常健康输入，共 30 用例，全部拒绝该池且只向另一个健康池派发新请求：

| 输入 | 观测 reason |
| --- | --- |
| 任意 200 / 缺失服务身份 | invalid_service |
| 200 / 错误 service | invalid_service |
| 404 / 看似正确的 JSON | http_status_404 |
| 200 / 非法 JSON | invalid_json |
| healthy=0 | invalid_healthy |
| healthy=-1 | invalid_healthy |
| healthy=1.5 | invalid_healthy |
| healthy 为字符串 | invalid_healthy |
| healthy 为布尔值 | invalid_healthy |
| healthy=null | invalid_healthy |
| healthy 超出安全整数范围 | invalid_healthy |
| 响应体超过配置上限 | body_too_large |
| 完全静默 | timeout |
| 持续小字节 trickle、始终不结束 | timeout |
| 超时后才返回看似正常的 JSON | timeout |

其余 6 用例：

- 恢复正确健康响应后，两池可恢复可用；本地路由状态明确包含 service、正整数 healthyCount、reason、TTL、剩余 TTL、过期时间与探测状态。
- /v1/models、/panel/、/status、/healthz 只取主池；主池健康失败后这些路径明确 503，辅池不接收请求。独立验证发往辅池时使用辅池测试凭证、移除客户端 x-api-key，发往主池时保留客户端测试鉴权。
- 全部 10 个分流端点在离线、未暂停、主坏辅好时派发辅池。
- 健康 TTL 为 180 ms、刷新周期为 700 ms 时，TTL 过期后两池均显示 stale，推理请求 503 且零上游接收；周期探测恢复后重新可派发。
- TTL 为 110 ms、探测超时 450 ms 时，在探测仍进行中也不使用过期健康缓存，明确 503；探测最终 timeout。
- 使用真实隔离 TCP 连接与原生 netstat.exe 验证 QQ offline→online→offline。独立 netstat 看到测试端口 52171 两条 ESTABLISHED 记录；路由 qq.raw 与 state 随之转换。QQ 在线且主坏时返回 503、辅池零接收，断开测试连接转离线后才允许辅池接收。测试配置将迟滞设为 0 以缩短等待，未改变任何生产配置。

## G6：17 用例，15/15

不重放共 10 用例：404、429、收到头后立即 reset、读完请求体后 reset、已发送部分响应后 reset，各自覆盖空和非空 POST。每用例发 8 次请求，实际同时覆盖主、辅两池。404/429 原样返回；reset 明确失败或截断。每个唯一请求 ID 的实际接收次数均为 1，没有跨池或同池重放。

流式与取消共 7 用例：

| 动态检查 | 观测结果 |
| --- | --- |
| SSE 首包、完整字节与持续活跃超时 | 8 次请求覆盖两池；首字节 1–6 ms；每次 6 次分块到达；含拆开 UTF-8 字符的原始字节最终完全一致；总耗时 462–480 ms，大于 requestTimeoutMs=240 ms，仍完整结束 |
| SSE 客户端取消 | 8 次请求覆盖两池；客户端首数据后取消，上游响应关闭且未正常 finish，关闭后没有继续写入 |
| 上传过程中取消 | 8 次请求覆盖两池；声明 1 MiB，只先发送 32768 bytes；客户端未结束上传时上游已经收到数据；取消后上游 requestAborted，未补发、未完成 |
| 持续上传超过空闲超时仍成功 | 每 70 ms 写入一块，8 块共 8192 bytes；总耗时 621 ms，大于 240 ms，返回 200 并完整读入 |
| 真空闲、响应头之前 | 约 250 ms 返回完整 504，上游连接关闭，仅一次接收 |
| 真空闲、响应头之后 | 约 245 ms 终止已开始的 200 SSE，客户端观测截断，上游关闭，仅一次接收 |
| 所有转发结束后的唯一性复查 | 107 个唯一请求 = 107 条接收记录；重复 ID 记录数为 0 |

响应头之后的空闲失败无法再改已经发送的 HTTP 200，因此以客户端明确观测截断、上游关闭为断言；没有将该场景误计为完整成功。

## 清理与边界

所有 HTTP 假池、模拟 QQ 端口和入口监听由操作系统动态分配，并显式拒绝生产网关端口。测试代码没有向 8788/8789/8790 发起任何请求，更没有真实 inference；没有读取账号 auths 或写生产状态。

9 个自建候选进程 PID 为 9448、42096、40188、43436、42212、37684、36024、11572、40876，全部通过候选自身清理流程 exit 0。9 个场景的 36 个监听端口均在进程和假服务关闭后逐一连接验证为已关闭。各场景临时配置、状态、候选副本与模拟数据已删除，临时 runtime 根目录也已删除。仅保留正式独立测试、结构化证据和场景 stdout 日志。

## 截至本阶段的 G/K 状态

| 评分项 | 独立得分 / 满分 | 状态 |
| --- | --- | --- |
| G1 | 15/15 | 已通过，见此前独立阶段报告 |
| G2 | 15/15 | 已通过，见此前独立阶段报告 |
| G3 | —/15 | 未测，启动候选仍待最终冻结通知 |
| G4 | —/15 | 未测，守护/helper 候选仍待最终冻结通知 |
| G5 | 25/25 | 本阶段通过 |
| G6 | 15/15 | 本阶段通过 |

| 否决项 | 当前独立结果 |
| --- | --- |
| K1 | 未触发；规格与 lock 开始/结束 HASH 一致 |
| K2 | 未触发；没有停止真实池、改账号数据凭证模型或真实模型调用 |
| K3 | 候选启动/守护冲突行为未测；本测试没有按端口杀任何进程 |
| K4 | 已覆盖 QQ 在线、辅池暂停与实际在线/离线变化；未触发 |
| K5 | 已覆盖空/非空 POST 的各类已派发失败，107 次接收无重复；未触发 |
| K6 | 本阶段未触发；自建进程与端口均关闭，临时数据删除；完整最终清理仍待 G3/G4 后复核 |

## 命令与可复核材料

```text
node --check docs/wb-gateway-repair-20261107/round1/g56-independent.cjs
node --check docs/wb-gateway-repair-20261107/round1/owned-router-lifecycle.cjs
node docs/wb-gateway-repair-20261107/round1/g56-independent.cjs
```

语法校验与正式测试均 exit 0。背景执行 job 为 `bash-f01e89997e0048f9`，已收取全部输出，结果为 69/69、无 fatal、全部清理通过、HASH 不变。

- [g56-independent.cjs](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/g56-independent.cjs>)：独立假池、客户端及动态断言。
- [owned-router-lifecycle.cjs](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/owned-router-lifecycle.cjs>)：仅用于自建进程退出的生命周期 hook。
- [g56-evidence.json](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/g56-evidence.json>)：69 项逐项结果、9 个进程的完整命令/端口/HASH/退出状态、SSE 时间与字节断言、107 条实际转发接收记录及清理证据。
- [stage12-report.md](<D:/deepseek harness/work_anki_enligish/docs/wb-gateway-repair-20261107/round1/stage12-report.md>)：先前 G1/G2 的独立阶段结果。

本报告只确认被指定 HASH 的路由候选与列出的动态行为，不代替尚未完成的 G3/G4，也不代替第二轮新 agent 的独立复核。
