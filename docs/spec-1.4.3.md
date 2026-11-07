# 1.4.3 规格：卡背「重写思路」

> 状态：已实施（1.4.3 待验收）。版本 `versionName 1.4.3` / `versionCode 6`；数据库 14 → 15。
> 决策来源见 [`decisions-1.4.2.md`](decisions-1.4.2.md) 与本文末「决策记录」。

## 0. 一句话

卡背上多一行「重写」，手机只打标记；电脑收到后走「生成 → 润色 → 评分」，下次冷启动 App 自动把新的三行思路写回本地库——不重装、不动复习进度、可撤销一次。

## 1. 不做什么

- 不生图、不存整卡、不产出 `hint` / `scene` / `imagePrompt` / 画面字段
- 不做「选中某一句」改写；重写粒度是**整段记忆思路**（`approach` 三行）
- 不碰复习进度、不碰正面、不碰卡背排版（排版仍由 1.4.2 的清单负责）
- 不回灌 anki（与 anki 原卡有意分叉）
- 不加动效

## 2. 架构

```
手机（背单词时）                      电脑（开机就在跑）
┌────────────────────┐   POST marks   ┌──────────────────────────┐
│ 卡背底部一行         │ ─────────────▶ │ rewrite-service (8765)   │
│  重写 / 模型下拉      │                │  队列(落盘) → 生成        │
│  折叠输入框          │                │            → 润色        │
│  状态小字            │ ◀───────────── │            → 评分(独立)  │
└────────────────────┘  GET results   └──────────────────────────┘
        ▲                                      │
        └── 冷启动/回前台：补发队列 + 拉结果 ◀──┘
```

- 手机端**永不**直接调模型；离线也能标记。
- 电脑端把每条结果落到按词的历史文件，手机按 `seq` 增量拉取。
- 换机/重装：手机拉一次全量历史即可恢复（`since=0`）。

## 3. 接口契约

所有接口同源挂在现有 `tools/cardback-editor/server.py` 的 8765 端口下，前缀 `/rewrite`。

### 3.1 `POST /rewrite/marks`

```json
{
  "marks": [
    {
      "clientId": "uuid-v4",
      "word": "abandon",
      "phonetic": "/əˈbændən/",
      "pos": "v.",
      "meaning": "v. 放弃；抛弃",
      "model": "ds-v41-flash",
      "instruction": "第三句太牵强",
      "markedAt": "2026-11-07T03:12:00+08:00"
    }
  ]
}
```

响应：`{"accepted": ["uuid"], "rejected": [{"clientId":"…","reason":"unknown model"}]}`

- 幂等：同一 `clientId` 重复投递只入队一次。
- 同词多条未处理标记：新的覆盖旧的（队列里按 `word` 去重，保留最新）。

### 3.2 `GET /rewrite/results?since=<seq>&limit=50`

```json
{
  "results": [
    {
      "seq": 17,
      "word": "abandon",
      "rev": 2,
      "approach": ["① …", "② …", "③ …"],
      "prev": ["① …", "② …", "③ …"],
      "updatedAt": "2026-11-07T03:20:11+08:00",
      "status": "ok"
    }
  ],
  "nextSince": 17,
  "more": false
}
```

- `since=0` 为全量（换机恢复用）。
- `status`: `ok` | `failed`（failed 时 `approach` 为空，手机只把标记标成失败）。

### 3.3 `GET /models`

```json
{"models":[
  {"id":"ds-v41-flash","name":"DeepSeek V4.1 Flash","provider":"wb","reasoning":"max"},
  {"id":"step5","name":"Step 5","provider":"wb","reasoning":"high"},
  {"id":"glm53","name":"GLM 5.3","provider":"wb","reasoning":"high"},
  {"id":"portdan-gpt","name":"GPT-6.1 Sol","provider":"portdan-gpt","reasoning":"high"},
  {"id":"portdan-grok","name":"Grok 4.7","provider":"portdan-grok","reasoning":"high"}
]}
```

### 3.4 `GET /rewrite/status` / `POST /rewrite/retry`

- `status`: `{pending, running, done, failed, items:[{word,model,state,error}]}`
- `retry`: `{"word":"abandon"}` → 把失败项重新入队。

### 3.5 面板

`http://127.0.0.1:8765/rewrite/`：待处理 / 今日成功 / 失败列表（带重试按钮）。与卡背编辑器同一页面风格，零依赖。

## 4. 电脑端

### 4.1 目录

```
tools/rewrite-service/
├─ service.py          # 队列、调度、HTTP 路由
├─ providers.py        # wb 网关 / portdan 两个供应商
├─ prompts/
│  ├─ generate.txt     # 六脉神剑（原样，来自 MindForge mnemonic-reference.txt）
│  ├─ polish.txt       # 只润三行（A/B 中适用思路的条目）
│  └─ judge.txt        # 评分（原 9 维，删掉生图维度）
├─ data/               # 队列、结果、历史（不入库）
└─ 启动重写服务.bat
```

### 4.2 密钥

从 `~/.dsh/.credentials.yaml` 读（扁平 YAML，取 `PORTDAN_API_KEY` / `PORTDANGPT_API_KEY` / `PORTDAN1_API_KEY`），wb 用固定本地 key `sk-wb-local-8788`。**任何密钥不写进仓库**。

### 4.3 模型与推理强度

| id | 供应商 | 实际型号 | reasoning |
|---|---|---|---|
| `ds-v41-flash` | wb | `cn:deepseek-v4.1-flash` | max |
| `step5` | wb | `cn:step-5-preview` | high |
| `glm53` | wb | `cn:glm-5.3` | high |
| `portdan-gpt` | portdan-gpt | `gpt-6.1-sol`（openai-responses） | high |
| `portdan-grok` | portdan-grok | `grok-4.7`（openai-responses） | high |

- wb 网关**忽略 `system` 角色**：提示词一律放 `user`。
- 换型号只改这张表（`providers.py` 里常量），手机端从 `/models` 动态读，不用发版。

### 4.4 处理链（每词）

1. **生成**：`generate.txt` + 四行词头（word/phonetic/pos/meaning）+ 用户的 `instruction`（有则附「上一版哪里有问题」），要求只输出 `{method, split, elements, derivation}`。
2. **润色**：`polish.txt` 只针对 `derivation` 三行（拆词可复述 / 谐音只一跳 / 一句一动作 / 删正确的废话 / 末步必须 `——>` 落到词义）。
3. **评分**：独立调用，只看成品三行，不给它前两步的过程；原 9 维去掉生图相关项，全部 ≥8 才算过。
4. 不过 → 把评分意见回灌重试 **1 次**；仍不过 → 记为 `failed`，手机上保留原卡。

- 并发 2；失败重试 2 次（网络类）。
- 服务启动时扫 `data/queue.json` 接着跑；`data/results/<word>.json` 每词最多 5 版。

### 4.5 已知坑

- **推理型模型的 token 预算**：`step-5-preview` 早先返回空 `content`（`finish_reason=length`），根因是生成提示词里还塞着整卡生图要求（约 6KB），推理把预算吃光。修法：`prompts/generate.txt` 缩成「只产三行推导」，生成/润色 `max_tokens` 保持 12000，评审 8192（长 issues 会把 JSON 截断）。
- **评分缺维度**：评委偶尔只给一两个维度打分；`min()` 会把它当成全过。缺的维度按 0 分计（fail-closed）。
- **中转站 grok 的并发**：两条并发时几乎必吃 502，串行连打则稳定（单次生成 40–75s、润色可到 190s）。`providers.py` 里给它单独加了进程内锁，同一供应商同时只放一条；代价是这条链很慢，一条词 5–8 分钟。
- **端口**：8765 是卡背编辑器与重写服务共用的端口；8790 是用户 WB 网关主池，测试 stub 请用 8795 以上。

## 5. 手机端

### 5.1 UI（只在卡背，正面不动）

位置：卡片与红黄绿三键之间，固定三行。

| 行 | 内容 |
|---|---|
| 1 | 左 `重写思路`；右 模型下拉（5 项，记住上次选择，不显示倍率） |
| 2 | 折叠输入框，占位「哪里有问题？留空＝整段重写」 |
| 3 | 状态小字 |

### 5.2 状态机

| 状态 | 按钮文案 | 状态小字 | 触发 |
|---|---|---|---|
| idle | `重写思路` | — | 无标记 |
| pending | `已标记 · 再点取消` | `已标记 · 待上传（再点一次取消）` | 已入本地队列，未成功投递 |
| queued | `已送电脑 · 再点重发` | `电脑已收到，排队重生中（再点一次可重发）` | 已投递，最多等 30 分钟，超时自动挂回 pending |
| done | `再重写一次` | `上次重写 11-07 11:15` + `撤销` | 已写回 |
| failed | `重试重写` | `上次没成功：<原因>` | 电脑返回 failed |

- 点按钮：写队列 → 立即尝试上传。
- pending 状态下如果用户改了输入框内容再点，**直接覆盖这一条**（同一 `word_id` 唯一，不产生第二条）；没改内容再点才是取消。
- queued 也允许再点重发：电脑端数据被清掉时不至于永久卡住。
- 卡背地址为空时点按钮：提示「先去我的页填电脑地址」，不入队。
- 冷启动 / 回前台：补发队列 → `GET /results?since=lastSeq` → 写库 → 刷新卡背，全程静默。同步只放在学习页的 resume 里，**不在 `Application.onCreate` 做**（会和加载词抢跑，出现库已更新、卡片仍旧文）；每轮同步后无条件按库刷新当前卡片。
- 结果认领：只认当前这条标记的 `clientId`；撤销后队列已删的结果、重发后迟到的旧结果都会被丢掉。
- 撤销只保留一次（写回 `prev_approach`）；退回种子原文时把 `source` 交还 `seed`，以后种子升级仍能覆盖它。

### 5.3 数据库（14 → 15）

```sql
ALTER TABLE tb_word_memory ADD COLUMN source TEXT NOT NULL DEFAULT 'seed'; -- seed | user
ALTER TABLE tb_word_memory ADD COLUMN rev INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tb_word_memory ADD COLUMN prev_approach TEXT;
ALTER TABLE tb_word_memory ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;

CREATE TABLE tb_rewrite_queue (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  word_id INTEGER NOT NULL,
  word TEXT NOT NULL,
  instruction TEXT,
  model TEXT NOT NULL,
  status TEXT NOT NULL,      -- pending | queued | done | failed
  error TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
```

- 种子导入（`WordRepository` 的 `upsertAll`）**跳过 `source='user'` 的词**，否则重写会被覆盖。
- 单词重写不改变 `tb_word_memory` 的复习相关字段。

## 6. 验收 8 条

1. 断网标记不丢；联网后自动补发。
2. 电脑跑完 → 下次冷启动该词三行变新，学习进度不变。
3. 撤销能回上一版，且只能回一次。
4. 种子 version+1 重新导入后，已重写的词不被覆盖。
5. 评分两次不过：原卡保留、标记显示失败、可重试。
6. 同一个词连点两次只算一条，后写的指令覆盖先写的。
7. 电脑不在线时打开 App：卡背正常、不报错、不转圈。
8. 下拉 5 个模型全部跑通；deepseek 为 max，其余 high。

## 7. 已知限制

- 电脑没开机的那一刻打开 App 拉不到，卡片保持原样，等下一次。
- 未填「卡背结构地址」时重写不可用（会提示）。
- 手机与电脑必须同一局域网。
- 生成/润色慢的模型会把一条词拖到 5–8 分钟（grok 最明显）；并发 2，攒批时排在后面的词要等。
- 五个模型不是等价的：DeepSeek 一轮就过最常见，Step5/GLM 常要第二轮，GPT 与 Grok 被评审判不过的比例更高。**判不过不写回**，卡背保持原样，属于设计内行为。
- **换机/重装恢复已可用**：清数据或换机后，填回电脑地址再启动一次，App 会以 `since=0` 拉全量历史并按 seq 顺序写回（实测同一词 rev 依次升到 4，卡背显示最后一次改写）。首次启动偶发读到被截断的响应时不会丢结果（不推进 `since`，下次回前台重试，单次请求最多重试 3 次）。
- 撤销只能回一次，且只覆盖本机最近一条；多设备之间不互相同步撤销。

## 8. 决策记录（用户原话）

- 「手机上只是个符号 到时候标记传到电脑上 电脑通过这个标记调用模型」
- 「我背完后标记上传到电脑一起重生 等到我下次打开前（也就是所谓的明天会自动刷新 并且不改变我的学习记录）」
- 「你先生成记忆思路再润色啊」
- 「生成文本并且润色agent必须与评分agent是两个agent 不能又当选手又当裁判」
- 「我们不要存卡，我们只要学习思路 因为我们现在不涉及生图」
- 「能不装新包就不装新包」
- 「不用显示倍率」
- 「多开智能体（别顶到网关上限了）」
