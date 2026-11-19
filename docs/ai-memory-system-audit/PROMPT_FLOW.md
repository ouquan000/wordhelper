# 真实运行链路：哪些标准进了哪一次模型请求

> 一切以代码实际调用为准。行号按 2026-11-19 的文件内容。
> 仓库内路径省略前缀 `D:\deepseek harness\work_anki_enligish\`。

---

## 1. 端到端链路（一次重写的完整生命周期）

```
[Android]  卡背「重写」按钮 + 可选的多行输入（200 字上限）
   RewriteBar.kt:99–185  instruction 输入框；:150–157 点重写 → onMark(instruction)
   LearningViewModel.kt:563  markRewrite(instruction)
        │
        ▼
   RewriteRepository.kt:409  enqueueAndSync()
        │  POST /rewrite/marks   { clientId, word, instruction, model, ... }
        ▼
[服务端]  service.py  store.py
   store.enqueue()  → queue.json  items[].state = queued
        │
        ▼  service.py:133–140  两个 worker 线程（CONCURRENCY = 2）
   store.claim(60)  →  state = running，stage = generate
        │
        ▼  service.py:84–86   ★ 生成之前先组装同词历史
   item["history"] = history.build(data_dir, word, instruction, scope=item)
        │      ← 读 feedback.jsonl（被点踩/被点赞的版本）+ queue.json（用户以前的原话）
        ▼
   pipeline.run(item)          pipeline.py:806–938
        │
        ├─(1) generate   pipeline.py:437–455 → _generate_once:385–434
        │        model = item["model"]（默认 ds-v41-flash）
        │        system = prompts/generate.txt   ← _prompt("generate")
        │        user   = _brief(item) + 用户原话硬约束 + [重试时] 评委软建议
        │        max_tokens=12000  temperature=0.9  timeout=300
        │        ↓ 解析 JSON，只取 method / split / elements / derivation
        │
        ├─(2) polish     pipeline.py:458–485
        │        model = 与上一步同一个模型
        │        system = prompts/polish.txt     ← _prompt("polish")
        │        user   = _brief(item) + 拆法 + 待润色的 3–5 步 + 用户禁令
        │        max_tokens=12000  temperature=0.4  timeout=240
        │        ↓ 只返回 {"derivation": [...]}；normalize 失败则退回草稿
        │
        ├─(3) judge      pipeline.py:675–766
        │        model = providers.judge_target(item["model"])  ★ 换一个模型
        │        system = prompts/judge.txt      ← _prompt("judge")
        │        user   = _judge_brief(item) + 新版成品 3–5 步
        │        max_tokens=8192  temperature=0.2  timeout=240
        │        ↓ 七维打分 + intent/same_method/history_repeat
        │
        └─ 判定 pipeline.py:773–802
             通过 = 六维全 ≥8 且「诉求落实」≥8（无用户意见时不做第7维）
                 或 「诉求落实」≥8 且六维最低 ≥6 且无硬红线 0 分
                 并且 本轮没有命中用户禁令（violations 为空）
             ├ 通过   → unverified=False → store.finish_ok() → 写 data/results/<word>.json
             └ 不过   → 带着 issues 重来一轮（MAX_ATTEMPTS = 2）
                        两轮都不过 → 取两轮里 _rank 最高的那版
                                    unverified=True, quality_rejected=True
                                    → store.finish_quality_rejected()（指数退避重试，不写回手机）
        │
        ▼
[回传]  GET /rewrite/results?since=<lastSeq>&limit=50      RewriteRepository.kt:602
   RewriteRepository.kt:906  applyResult(dto)  三层守卫：clientId ↔ wordId ↔ 当前标记
        → tb_word_memory  source=user, rev+1, prev_approach=旧文
        → LearningViewModel.kt:303  liveApproach: StateFlow
        → LearningScreen.kt:196 / :637  卡背自动换文（无需重进页面）
```

**关键结构事实**：三次调用是**串行**的，**同一个词一次只跑一套**；`generate` 与 `polish` 用**同一个模型**，`judge` 强制换另一个（`providers.py:136–145`，注释「不能又当选手又当裁判」）。

---

## 2. 每次请求里到底拼了什么

### 2.1 P1 `generate`（生成）

`system` = `prompts/generate.txt` **整份文件，逐字节**（`pipeline.py:144–146`，`utf-8-sig`，**每次调用现读磁盘、无缓存** → 改文件即时生效，不用重启）。

`user` = `pipeline.py:210–227 _brief(item)` + L385–434 追加：

| 段 | 内容 | 来源 |
|---|---|---|
| ① | 单词 / 音标 / 词性 / 释义 | `queue.json` 的 item |
| ② | 【用户这一版指出的问题（原话，未改写）】 | item 的 `instruction` |
| ③ | 【旧版思路（别再抄一遍，换个更顺的）】 | item 的 `currentApproach` |
| ④ | 【这个词的历史…】 | `history.build()` 的四段拼接 |
| ⑤ | 「用户这一版的原话是硬约束，优先级高于任何评委意见…」 | 代码里写死的字符串 |
| ⑥（仅重试轮） | 用户原话**再照抄一遍** + 「上一版没过。下面是评委的软建议（参考为主，可反驳）」 | 代码 + 上一轮 judge 的 issues |

### 2.2 P2 `polish`（润色）

`system` = `prompts/polish.txt` 整份。

`user` = `_brief(item)` + 拆法 + 待润色的 3–5 步 + （有禁令时）`【用户禁令·润色只许收紧】`。

**注意**：polish 的 `user` 里**没有**历史块中的「被点赞版本」以外的正样本提示 —— 正样本在 `_brief` 的历史段里，随 `history.build()` 一起传。

### 2.3 P3 `judge`（评委）

`system` = `prompts/judge.txt` 整份。

`user` = `pipeline.py:230–254 _judge_brief(item)`：

| 段 | 内容 | 刻意的取舍 |
|---|---|---|
| ① | 单词 / 音标 / 词性 / 释义 | — |
| ② | 【用户这一版指出的问题（原话，未改写）】 | 原话照给 |
| ③ | 【上一版成品（被用户否掉的那一版 3–5 步）】`prev[:5]` | 只给成品 |
| ④ | 【这个词的历史…】 | 四段摘要 |
| ⑤ | 【新版成品 3–5 步】 | 待判文本 |

**刻意不给的**：生成过程、`split`、`elements`、模型名、prompt 原文。`judge.txt` 因此只能判「结果像不像人话、链不链得住」，不能看到「模型是怎么被指使的」。这是有意设计 —— 避免评委按提示词判断而非按成品判断。

### 2.4 传输层的一个硬约束

`providers.py:233–243`：**本机 WB 网关（8788）忽略 `system` 角色**，所以真正发出去的是
```
messages = [{"role": "user", "content": system.rstrip() + "\n\n" + user}]
```
即 **prompt 文件内容与 user 内容在传输层被拼成一条 user 消息**。portdan 通道（GPT-6.1 Sol / Grok 4.7）走 `/responses`，`system` 进 `instructions`，且**不传 temperature**。

> 含义：判断「某条规则有没有生效」时不能看 role 划分，要看 prompt 文件正文与 `user` 拼装函数。

---

## 3. 真正进入每次请求的文件（白名单）

| 文件 | 进入哪一步 | 进入方式 |
|---|---|---|
| `tools/rewrite-service/prompts/generate.txt` | P1 | `_prompt("generate")` 整份作 system |
| `tools/rewrite-service/prompts/polish.txt` | P2 | `_prompt("polish")` 整份作 system |
| `tools/rewrite-service/prompts/judge.txt` | P3 | `_prompt("judge")` 整份作 system |
| `tools/rewrite-service/pipeline.py`（`_brief` / `_judge_brief` / `banned_terms` / 常量） | P1 P2 P3 | 拼进 `user`，或决定参数与判定 |
| `tools/rewrite-service/history.py` | P1 P2 P3 | 产出 `_brief`／`_judge_brief` 的第 ④ 段 |
| `tools/rewrite-service/data/feedback.jsonl` | 间接 | 经 `history.build()` 摘要成「被点踩/被点赞的版本」 |
| `tools/rewrite-service/data/queue.json` | 间接 | 经 `history.build()` 取出用户历史原话 |
| `tools/rewrite-service/providers.py` | 全部 | 模型选择、`judge_target`、重试与冷却 |

**就这 8 个。** 除此之外没有任何文件被读进模型请求。

---

## 4. 存在于文件里、但**没有**进入任何请求的标准

| 标准 | 原文件 | 为什么没进 |
|---|---|---|
| **Grok G1–G16 全部 16 条** | `originals/style-standards/reply-C-grok.md` | **只以压缩形式进入 3 条**（口吻分层、梗密度、硬边界/禁词）；G1 抓手、G3 不许歪读音、G5 梗要能当记忆钩、G11 例句语气、G14 安全线等**逐条判定标准没有进入任何 prompt** |
| Grok G4 / G6–G10 / G12 / G13 | 同上 | 绑定 `hint` / `scene` / `exampleZh` / `collocations` 四个字段，**App 运行时不产出这些字段**（`generate.txt` 开篇明说不写例句/配图/场景） |
| **Claude A1「每段 ≤12 字」** | `originals/style-standards/reply-A-opus.txt:89` | 现行 `polish.txt:10` 只说「每行一整句完整的话」，**没有 12 字限制**；且 1.4.4 明确删除过这条（`_recon/acceptance-1.4.4.md:136`） |
| **Claude A5 翻译腔黑名单** | `reply-A-opus.txt:97` | 黑名单（进行+名词 / 使得 / 对于…而言 / 作为 / 通过…来 / 在…的情况下 / 非遭受义的被 / 同句两个他的她的）**一条都不在** `polish.txt` 与 `judge.txt` 里 |
| **Claude A6 搭配≤8 字 / A7 字数复核** | `reply-A-opus.txt:99–101` | 无搭配字段；A7 被「不数字数」取代 |
| **Claude B3 可回忆性五勾** | `originals/style-standards/reply-B-opus.txt` | 依赖 hint 字段 |
| **Claude B4 场景四层** | 同上 | 依赖 scene 字段 |
| **Claude B5 A/B 换角度** | 同上 | 依赖 scene 字段 |
| **`polish-spec-v1.md` 全部** | `originals/style-standards/polish-spec-v1.md` | 被 v2 完全覆盖（v1 的 R1–R5 = v2 的 B1–B5），是**文档层**产物，从未被任何代码读取 |
| **`polish-spec-v2.md` 全部** | `originals/style-standards/polish-spec-v2.md` | 同上，是**人读的规范**，代码不读它；它派生出的 12 字线已被删 |
| **`polish_v3.py` 整套** | `originals/style-standards/polish_v3.py` | 一次性的**离线批量脚本**（跑 5 个词 bit/time/work/abandon/rope）。它把 A+B+G 合并成 prompt 的成果被人工搬进了 `polish.txt`，但**脚本本身不在服务调用链上** |
| **`polish_batch.py` / `polish_export.py` / `polish_audit.py` / `polish_sample.py`** | 同目录 | 全是离线批处理/审计/取样工具，服务运行时一次都不 import |
| **`mnemonic-reference.txt` 的记忆三板斧（具体化/新奇化/情绪化）** | `originals/methodology/mnemonic-reference.txt:20–23` | 随 scene 字段一起消失 |
| **`mnemonic-reference.txt` 的 22 字段 schema 与 imagePrompt 骨架** | 同上 `:41–67` | App 不生成配图 |
| **`task-card.txt` 分镜 / 可画性规则** | `originals/methodology/task-card.txt` | 只在 MindForge 侧的 `run.py` 里用（`run.py:384`），不在本仓库任何调用链上 |
| **`task-v6slots.txt`** | `originals/methodology/task-v6slots.txt` | 同上，MindForge 另一条产线（v6 分镜槽位），与本 App 无关 |
| **4 份 `pipeline.config*.json`** | `originals/methodology/*.json` | MindForge 流水线配置，本 App 不读 |

### 4.1 一个容易误判的点：「口吻标准」到底进没进

**进了，但是压缩版，且只进了措辞层，没进判定层。**

- `polish.txt:13–22` 有完整的**八种口吻**（冷幽默/损友/正经人跑偏/双关/带体温/梗王/毒舌/荤），每种一句描述 + 默认口吻是「带体温」。
- `polish.txt:24–26` 有**梗的密度**（荤或地狱笑话整卡最多一处）。
- `polish.txt:28–30` 明确**允许**冷梗/地狱笑话/成人性暗示/双关，条件是不改音不改义。
- `judge.txt:38–40` 把口吻变成**门槛**：整卡只许一个主口吻，混着来判不过；荤或地狱笑话超过一处判不过；出现教材腔判不过。

**没进的**：Grok 给每种口吻配的「适用词类 / 写法 / 判过条件 / 判不过条件 / 正例 / 反例」——`polish.txt` 只留了「写法」一句，判据和例子都丢了。判「口吻是否统一」实际上退化成了评委的主观感受（这也解释了为什么 judge 的「像人说话」维平均 8.80 却和 66% 踩率脱节）。

---

## 5. 用户原话的三条真实通路

用户的原话（`instruction`）是这个系统里权重最高的输入，它有**三条**独立通路，缺一不可：

| 通路 | 位置 | 作用 |
|---|---|---|
| **① 代码层禁令抽取** | `pipeline.py:260–305 banned_terms()` + `:327–374 _text_hits/_draft_hits` | 用正则从原话里抽「不要/别/禁止/不能用/别再 + 元素」，映射到六招键，然后**机械扫描**草稿的 `split`/`elements`/`derivation`。命中就当轮重生成，且 **`violations` 非空时 judge 给满分也判不过**（`run()` 里 `_passed(...) and not draft["violations"]`）。末行可用「最后提一嘴」放行。 |
| **② Prompt 层硬约束声明** | `generate.txt:28–32` + `pipeline.py:385–434` 追加句 | 告诉模型「用户原话优先级高于任何评委意见」。 |
| **③ 评委层第 7 维** | `judge.txt:41–47`「诉求落实」 | 判「这版有没有真的解决用户提的问题」。无用户意见时该维**不判**（`pipeline.py:675–766` 会显式告诉 judge 不要输出这个键）。 |

**三条通路的一致性风险**：①是机械匹配（可能误伤/漏抽），②是自然语言（模型可忽略），③是第三方判断（可能和用户本意不一致）。当前没有机制校验三者结论是否一致 —— 只有 ① 具备「一票否决」的硬权力。

---

## 6. 反馈（👍/👎）在链路里的位置

```
[Android] 卡背 👍/👎
   LearningViewModel.kt:628  submitRewriteFeedback(rating, instruction = "")
        → RewriteFeedback.kt  tb_feedback（本地先落库，DB19 起带 approach_rev）
        → RewriteRepository.kt:754  submitFeedback() → POST /rewrite/feedback
        ▼
[服务端] service.py:213–223  _feedback()
        → store.add_feedback()  pipeline.py 之外，**只追加 feedback.jsonl，不触发任何重写**
        ▼
[下一次重写时]  history.build() 读回 → 变成 P1/P2/P3 的【这个词的历史…】第 ②③ 段
```

**关键事实**：
1. **👎 不会自动触发重写**（`service.py:213–223` 注释原文：「卡背 👍/👎（1.4.6）：只落盘，不触发任何重写」）。
2. **反馈不进 judge**。judge 只在被调用时读 `history`，而 `history` 是**重写开始时**组装的快照；所以「这次评审判不到用户还没投的票」。
3. **`feedback.instruction` 在历史数据里 136 条全为空**（1.4.7.1 之前 App 没传），`history.py:85–107` 因此必须去读 `queue.json` 的 `instruction`。这是 `history.py` 里明确注释过的历史包袱。

---

## 7. 配置与参数一览（可直接核对）

| 项 | 值 | 位置 |
|---|---|---|
| 通过线 | 每一维 ≥ 8 | `pipeline.py:27 PASS_LINE = 8` |
| 诉求维下限 | ≥ 6 | `pipeline.py:29 COMPLIANCE_FLOOR = 6` |
| 管线内最大轮次 | 2 | `pipeline.py:63 MAX_ATTEMPTS = 2` |
| 步数硬约束 | 3–5 行，末行必须含「——>」 | `pipeline.py:72,167–189` |
| 生成温度 / 上限 | 0.9 / 12000 / 300s | `pipeline.py:385–434` |
| 润色温度 / 上限 | 0.4 / 12000 / 240s | `pipeline.py:458–485` |
| 评委温度 / 上限 | 0.2 / 8192 / 240s | `pipeline.py:675–766` |
| 并发 worker | 2 | `service.py:32` |
| 评委候选 | deepseek-v4.1-flash / glm-5.3（reasoning=high），且**必须与生成模型不同** | `providers.py:91–145` |
| Prompt 读取 | 每次调用现读磁盘，无缓存 | `pipeline.py:144–146` |
| WB 网关 | 忽略 system 角色，prompt 与 user 合并成一条 user 消息 | `providers.py:233–243` |

---

## 8. ★ 第二条链路：App 内「AI 助记技巧」（不在上面那条链上）

上面 §1–§7 讲的全是**服务端重写链路**。App 里还有一条**独立**的助记生成，走用户自填的 OpenAI 兼容接口，与本仓库的 `prompts/*.txt` 毫无关系。

```
[Android] 卡背 / 学习页「AI 助记技巧」按钮
   LearningViewModel.kt:1199 / :1208 / :1223 / :1231   activeType = AIContentType.MEMORY_AID
   LearningAiPanel.kt:437  「AI 助记技巧」
        │
        ▼
   AIRepository.kt:125–130
        config = normalizeConfig(loadConfig())        ← AIConfig{enabled, apiBaseUrl, apiKey, modelName}
        validateConfig(config, requireEnabled = true) ← 未配置就直接失败，不发请求
        prompt = PromptTemplates.build(type, normalizedQuery)
        │
        ▼
   PromptTemplates.kt:54–68  buildMemoryAidPrompt(word)
        system  = "你是英语单词记忆教练。必须严格按模板输出，内容简短、可直接复习，不得输出多余段落。"
        user    = 请为单词 "$word" 生成助记内容，严格按以下格式输出：
                  【词根词缀】<一句话词根/词缀拆解>
                  【联想记忆】<一句话谐音或场景联想>
                  【复习提示】<一句话记忆抓手>
                  规则：1. 每段 1 句话，总字数不超过 120 字。 2. 只输出上述模板，不要添加解释。
        │
        ▼  AIRepository.kt:196–201
   POST {apiBaseUrl}/chat/completions
        model = config.modelName（默认 "gpt-3.5-turbo"）
        temperature = 0f（type != null 时强制为 0）
        max 重试 MAX_REQUEST_ATTEMPTS 次（429/5xx/超时可重试，AIRepository.kt:232–238）
        │
        ▼
   AIContentFormatter.normalize(MEMORY_AID)  ← AIContentFormatter.kt:41–57
        若含「【词根词缀】」且含「【联想记忆】」则原样保留；
        否则按行硬拆成三段（缺行给「（未提取到…，请重试）」占位）
        │
        ▼
   AICache 落库（wordId + type + aiContent + modelName + createdAt）→ AI 面板显示
```

### 这条链路与主链路的差别（逐项）

| 项 | 主链路（§1–§7） | 这条链路 |
|---|---|---|
| 标准来源 | `prompts/generate.txt` 等三份文件 | `PromptTemplates.kt` 里的 Kotlin 字符串 |
| 步数 | 3–5 步推导 | 三段固定标签 |
| 方法 | 六脉神剑，每词主打一招 | **词根词缀 + 联想 都要求给** |
| 字数 | 每行 25–60 字、3–5 行 | **总 ≤120 字** |
| 口吻/幽默 | 八口吻 + 梗密度 + 禁教材腔 | **无** |
| 评委 | 有（七维 + 两轮 + 禁令一票否决） | **无** |
| 历史/反馈 | `history.build()` 读点踩/点赞/原话 | **不读** |
| 模型 | 服务端固定六个，`judge_target` 换模型 | 用户自填，默认 `gpt-3.5-turbo` |
| 输出去哪 | `data/results` → 回写 `tb_word_memory` → 卡背 `liveApproach` | 本地 `AICache`，只在该 AI 面板显示 |
| 有没有文档 | 有（`docs/spec-1.4.3*`、`HANDOFF.md §3` 架构图） | **没有任何规格文档；不在 HANDOFF 架构图里** |

**审查提示**：这条链路的产物**也**会出现在卡背上（AI 面板里），用户无法区分哪条来自哪套标准。而本归档的 134 条反馈、26 对 judge 配对、全部 `results/*.json`，**只覆盖主链路**，与这条无关。
