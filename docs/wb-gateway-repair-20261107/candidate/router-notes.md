# WB 路由候选实现说明

范围仅为冻结规格 G5/G6 的路由实现与隔离测试。本目录未部署到线上，未停止或重启主池、辅池、入口或守护。未调用真实模型，也未读取真实账号、Clash 凭证或用户配置。独立两轮验收由父 agent 另行组织；此处仅记录实现方的开发测试，不给分。

冻结规格：[acceptance-spec.md](../acceptance-spec.md)。开始、每个测试夹具清理后、测试结束及交付时的 SHA-256 均为：

```text
c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91
```

## 文件与边界

- [wb-router.js](./wb-router.js)：可部署的路由脚本，仍从自身 `__dirname` 读取配置/状态并写路由日志。交付目录中没有真实配置、状态或日志。
- [wb-router.test.cjs](./tests/wb-router.test.cjs)：Node 原生测试，动态端口假主池/假辅池/假 QQ listener；复制路由到独占临时目录后运行。
- [isolated-router-child.cjs](./tests/isolated-router-child.cjs)：仅测试使用，在子进程中替换 `netstat.exe` 的执行结果以控制 QQ 在线/离线；生产路由不加载此文件。
- [原线上脚本](D:/deepseek_v4.1flash1017/workbuddy2api-panel/run/wb-router.js) 全程只读。未改冻结规格或锁文件，也未操作 git 或 globalguard。

## 路由行为

两池启动时并发 GET `/healthz`，两次有界探测完成后才监听。初始值为不健康，不假定任意一池可用。周期探测分别单飞，不会因另一池缓慢而阻塞自己的刷新；完成回调必须仍持有自己的 generation，超时或取消后的回调无法恢复旧状态。

健康必须同时满足：HTTP 200、正文是合法 JSON、`service === 'workbuddy2api'`、`healthy` 为大于 0 的安全整数。404、任意 200 文本、错误身份、0/负数/小数/字符串/布尔 healthy 都失败。只缓存有大小限制的健康响应；推理正文从不缓存。

| 状态 | 新分流请求 |
| --- | --- |
| QQ 离线、辅池未暂停、两池健康且未过期 | 保留原平滑加权调度，默认 5:3 |
| 同上、主坏辅好 | 仅辅池 |
| 同上、主好辅坏 | 仅主池 |
| QQ 在线或 `auxPauseUntil` 尚未到期 | 仅主池；主坏则明确 503 |
| 两池都不健康或健康已过期 | 明确 503 |
| `/panel`、`/panel/*`、`/status`、`/healthz`、`/v1/models` 以及全部非分流路径 | 始终主池；主池不健康则 503 |

保留原 10 个分流端点：`/v1/chat/completions`、`/v1/responses`、`/v1/images/generations`、`/v1/images/edits`、`/v1/embeddings`、`/v1/messages`、`/messages`、`/v1/search`、`/v1/fetch`、`/v2/chat/busy`。

本机 `/_router/status` 的 main/aux 均提供 `healthy`、`reason`、`service`、`healthyCount`、`checkedAt`、`ttlMs`、`ageMs`、`remainingTtlMs`、`expiresAt`、`probing`。原中文 `stateMeaning`、QQ 状态、迟滞、权重、辅池暂停和计数保留。健康失败、错误身份、超时或 stale 可直接分辨。

## 配置与兼容性

保留原配置键和默认值，包括入口 8788、主 8790、辅 8789、QQ 6199、在线迟滞 180000ms、离线迟滞 300000ms、5:3 权重、`auxPauseUntil` 和请求超时 300000ms。辅池仍改写 Bearer key 并丢弃 `x-api-key`。启动仍沿用持久化 online/offline，无历史状态时默认 online。

新增可选健康配置：

| 键 | 默认 | 有效范围 | 用途 |
| --- | --- | --- | --- |
| `healthTimeoutMs` | 2500ms | 20–10000ms | 健康请求的绝对截止时间；持续滴流也不能拖延 |
| `healthMaxBytes` | 16384B | 128–65536B | 健康正文上限，另限响应头 8192B |
| `healthStaleMs` | 15000ms | 20–120000ms | 健康样本有效 TTL，按每次完成时间计算 |

现有 `auxHealthMs`（默认 10000ms）用于两池各自的刷新间隔。状态过期后马上不可选，刷新期间不会延长旧成功样本的有效期。健康是有 TTL 的快照，故障在后续探测或到期前可能尚未反映；此时真实转发失败也只失败一次，绝不跨池重放。

`requestTimeoutMs` 继续表示上游 socket 无活动超时，使用 `upstream.setTimeout`。持续输出的长 SSE 不按总时长被截断。没有覆盖 Node 服务端的 requestTimeout/headersTimeout 默认值。

## 一次派发与清理

删除旧 aux→main 重试分支。一次请求只创建一个上游请求；404/429 原样传递，响应前 socket reset 返回 502，真正 idle 返回 504；已发响应头后的断连终止下游。以上均不重放，也不把某次推理错误当成另一池可重放的依据。

请求体 `req.pipe(upstream)`、响应 `upRes.pipe(res)` 直接转发，SSE 到头即 flush。客户端响应连接提前关闭、上传 aborted 或异常时销毁上游；正常 `req.close` 只在 `req.aborted || !req.complete` 时取消，不会误杀已结束上传但仍在输出的 SSE。上游提前完成而客户端上传尚未结束时，解除上传 pipe 并关闭该上游请求。关闭流程清理周期 timer、正在探测的请求、QQ 子进程、活动转发、keep-alive agent 和入口 socket；启动探测阶段也能关闭。

`toMain`/`toAux` 现在计派发尝试数，因此响应前 reset 也可观察；`auxFail` 计辅池传输错误，不再表示发生过重放。

## 开发验证

工作目录为 `D:/deepseek harness/work_anki_enligish`：

```bash
node --check docs/wb-gateway-repair-20261107/candidate/wb-router.js
node --test --test-concurrency=1 docs/wb-gateway-repair-20261107/candidate/tests/wb-router.test.cjs
```

最终代码完成后的测试结果：12 tests，12 pass，0 fail，0 cancelled，0 skipped，约 7.12 秒。重要观测：

- 80 个假 POST 精确 50 主 / 30 辅；覆盖全部分流端点、正文和鉴权改写。
- 主池坏时分流用辅池，main-only 路径均 503；两池坏时 503。
- 两池分别验证任意 200、错误身份、404、无效 healthy、超大正文；恢复后可重新选用。
- QQ 在线、辅池暂停、无历史的安全默认均禁止借辅；QQ 测试只修改临时配置的迟滞到 60/80ms，生产默认仍 180/300 秒。
- 健康探测并发启动；启动中停止不会监听；滴流响应绝对超时；慢探测期间旧成功到 TTL 后失效；每池最大并发探测为 1；延迟旧响应不能覆盖后来失败。
- 404、429、响应前 reset 覆盖空/非空假 POST，每个请求假上游记录严格 1 条；响应后 reset 也无重放。
- 未结束的上传正文已能到达假上游；SSE 首包早于 400ms 延迟尾包，完整字节和 UTF-8 尾包保持。
- idle 配置 180ms，假上游每 60ms 输出，总 480ms 的 SSE 完整完成；真正 idle 的请求返回 504。
- SSE 取消与未结束上传的取消均关闭假上游连接。

所有夹具的测试子进程均断言正常 exit 0，假服务关闭且独占临时目录删除。最终检查 `wb-router-isolated-*` 临时目录为空。没有留下后台 job。未对真实池做 POST 或为了测试停真实池。

交付时 SHA-256：

```text
candidate/wb-router.js
2262451f13e4dcef160bb133d1fd17754aa56f0a4ace45c069ea0ebf59c7ce0f

原线上 run/wb-router.js（与读取时一致）
8628b0b611a511be41c0586cef9ac27cb50f9f037f192b527b9fae0cca47bf33
```

本说明不代替独立验收，也不报告已完成部署。线上安装、部署前在途确认、线上 GET 核验与两轮独立结果均由父 agent 执行。
