# 卡背重写服务（1.4.3 电脑端）

手机在卡片上点「重写」→ 这里排队 → 生成 / 润色 / 评分 → 手机按 `seq` 增量拉走。
契约见 `docs/spec-1.4.3.md`，本文只说怎么跑、东西在哪。

## 启动

**双击 `启动重写服务.bat`** —— 它先探 8765：已经在跑就直接开面板，没跑就起
`tools/cardback-editor/server.py`（重写服务的路由挂在同一个 8765 上，手机和编辑器同源）。

手动等价命令：

```bat
cd /d "D:\deepseek harness\work_anki_enligish\tools\cardback-editor"
py -3 server.py            :: 面板 http://127.0.0.1:8765/rewrite/
```

只想单独调试重写服务、不启动编辑器：

```bat
cd /d "D:\deepseek harness\work_anki_enligish\tools\rewrite-service"
py -3 service.py --port 8799
```

前提：**wb 本地网关要在 8788 上跑着**（`http://127.0.0.1:8788/v1`）。portdan 的两条
是外网直连，不需要网关。

## 开机自启（需要你自己点一下，不写注册表）

1. `Win + R` → 输入 `shell:startup` → 回车，打开「启动」文件夹。
2. 右键 `启动重写服务.bat` → 创建快捷方式，把快捷方式拖进那个文件夹。
3. 下次登录就会自动起。不想要了就删掉快捷方式。

想做静默自启（不弹黑窗），第 2 步改成在快捷方式属性里把「运行方式」设为「最小化」。

## 接口

都挂在 8765，前缀 `/rewrite`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/rewrite/marks` | `{"marks":[…]}`，按 `clientId` 幂等；同词未处理的标记新的覆盖旧的；未知模型进 `rejected` |
| GET | `/rewrite/results?since=&limit=` | `since=0` 拉全量；`nextSince` 单调递增，`more` 表示还有下一批 |
| GET | `/models` | 5 个型号（手机动态读，换型号不用发版） |
| GET | `/rewrite/status` | 队列计数 + 最近 30 条 + 今日成败 |
| POST | `/rewrite/retry` | `{"word":"abandon"}` 把这条失败的重新入队 |
| GET | `/rewrite/word?word=` | 这个词的历史版本（含 superseded 之前的版本） |
| GET | `/rewrite/` | 面板页（零依赖，浅色，刷新看队列） |

## 型号

| 公开 id | 上游 | 推理强度 | 供应商 |
| --- | --- | --- | --- |
| `ds-v41-flash` | `cn:deepseek-v4.1-flash` | max | wb 网关 |
| `step5` | `cn:step-5-preview` | high | wb 网关 |
| `glm53` | `cn:glm-5.3` | high | wb 网关 |
| `portdan-gpt` | `gpt-6.1-sol` | high | portdan（openai-responses） |
| `portdan-grok` | `grok-4.7` | high | portdan（openai-responses） |

评分固定换一个和生成不同的脑子（默认 `cn:glm-5.3`，可用环境变量
`REWRITE_JUDGE_MODEL` 指定）。同一个模型自评等于又当选手又当裁判。

## 处理链

生成 → 润色 → 评分，三段各发一次独立请求；评分只拿到「词 + 成品」，拿不到前两段的
过程。每一维都要 ≥8 分才收；不过就把评委的意见还给选手重来一次，第二次仍不过记
`failed`（`approach` 为空，手机看到失败态）。

并发 2 个工人；网络类错误（超时 / 5xx / 429 / 空 content）每次调用内部重试 2 次。

## 数据与日志

全在 `tools/rewrite-service/data/`，删掉就是清空重来：

```
data/queue.json              队列（重启读回来，上次 running 的退回 pending）
data/state.json              全局 seq
data/results/<word>.json     每词最多 5 版成功 + 3 次失败
data/logs/service.log        每段耗时、每轮分数、失败原因（超 2MB 自动截半）
```

密钥不落盘：运行期从 `C:\Users\<你>\.dsh\.credentials.yaml` 现读现用。
换目录用环境变量 `REWRITE_CREDENTIALS` / `REWRITE_DATA_DIR`。

## 排障

- **面板打不开**：8765 没起。先看 `data/logs/service.log` 最后几行。
- **一直是 failed，写着「三次失败」**：wb 网关没跑，或 8788 被别的进程占了。
- **`HTTP 401`**：`.credentials.yaml` 里 `PORTDANGPT_API_KEY` / `PORTDAN1_API_KEY` 没了。
- **端口被占**：`scripts` 里别人占了 8765 的话，用 `server.py --port 8767`，面板地址跟着换。
- **想只重跑某一个词**：面板上那条 `failed` 后面点「重试」，或
  `curl -X POST http://127.0.0.1:8765/rewrite/retry -d '{"word":"abandon"}'`。
