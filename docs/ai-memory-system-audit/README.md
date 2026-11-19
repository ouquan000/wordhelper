# AI 记忆卡系统 · 全量资料归档（供外部审查）

本目录把《不爱背单词》项目里所有与 **AI 记忆卡** 有关的资料集中到一处：记忆方法体系、语言表达与幽默规范、用户真实反馈、以及这些标准在运行时的真实接入情况。

**这是一份只读盘点，不是设计文档。** 归档过程中没有修改任何运行代码、Prompt、配置、数据库或 UI。所有结论以**文件实际内容和代码实际调用**为准，不采信文档自述 —— 本项目历史上多次出现「文档说 A、实现是 B」的情况（见 `RULE_CONFLICTS.md`）。

- 快照时间：2026-11-19（+08:00）
- 归档来源仓库：`ouquan000/wordhelper`（本仓库）+ 两个**仓库外**的本地目录（见下）
- 审查目标：让外部评审能判断「哪些标准真的在起作用、哪些只是文件、哪些互相打架、哪些根本没找到」

---

## 0. 一句话结论

> 这个系统里有**两套互不相同的记忆卡标准**：一套是 MindForge 时期为「22 字段整卡」写的六脉神剑（含场景/提示/例句/配图），另一套是 App 重写服务只改「3–5 步推导」的窄口径版本。运行中的三份 Prompt 只继承了前者的**方法选择规则**和后者的**口吻规则**，而语言风格领域最有价值的三份原始标准（Claude 的 A/B 两套 + Grok 的 G1–G16）里，**真正进了每次请求的只有：口吻分层、梗密度上限、教材腔禁词表，加上 B1/B2/A2/A3 被压成一句话的简版**；A1 的 12 字硬线、A5 的翻译腔黑名单、B3–B5、以及 G1–G16 里所有绑 hint/scene/例句/搭配的条款，全部停留在文件里。
>
> 另外，**同一个 App 里其实还并存着第二套助记生成**（卡背「AI 助记技巧」，`PromptTemplates.kt` 硬编码，走用户自填的 OpenAI 兼容接口）。它的方法观与主链路**相反**：固定要求「词根词缀 + 联想」同时给，总字数 ≤120 字，没有任何口吻与评分要求。它不在任何规格文档里，也不在这份归档的反馈统计里 —— 详见 `PROMPT_FLOW.md §8` 与 `RULE_CONFLICTS.md §F`。

---

## 1. 目录结构

```
docs/ai-memory-system-audit/
├── README.md                     ← 本文件：范围、结论、阅读顺序、脱敏说明
├── INVENTORY.md                  ← 逐项台账：资料名 / 原路径 / 归档路径 / 用途 / 是否参与运行
├── PROMPT_FLOW.md                ← 真实调用链：哪些标准进了哪一次模型请求（带代码行号）
├── RULE_CONFLICTS.md             ← 已确认的冲突 / 重复 / 覆盖关系，逐条引用原文位置
├── MISSING_MATERIALS.md          ← 确实找不到的资料（不补写、不伪造）
├── originals/                    ← 原文，逐字节保留（仅三处凭据类字段打码，见 §5）
│   ├── methodology/              ← 记忆方法体系（MindForge 源头）
│   ├── style-standards/          ← 语言表达 / 幽默 / 口吻标准及其工具脚本
│   └── samples/                  ← 标准落地后的实际样张与审计记录
├── feedback-samples/             ← 匿名化后的 👍/👎 样本 + 反馈分析报告
└── experiments/                  ← 三个实验的统计结论与适用范围（原始大文件未入库）
    ├── 01-judge-vs-feedback-calibration.md      ← judge 分与用户投票的配对校准（26 对）
    ├── judge-vs-feedback-pairs.jsonl           ← 上述 26 对的原始配对数据
    ├── 02-method-vs-satisfaction.md             ← 六种记忆方法各自的踩率
    └── 03-offline-polish-batch-20261105.md      ← 2026-11-05 全量 5250 词离线润色实验
```

**关于「原文优先」**：`originals/` 下的文件是从原路径直接复制的副本，未做任何改写、精简、合并或重排。少数文件开头带有抓取时的界面噪声（Antigravity 侧边栏），也**原样保留**，并在 `originals/style-standards/README.md` 中标注了正文起始行，方便阅读时跳过。

---

## 2. 资料来源（三个物理位置）

| 代号 | 位置 | 性质 | 说明 |
|---|---|---|---|
| **R** | `D:\deepseek harness\work_anki_enligish\` | 本仓库 | App 源码、重写服务、`docs/` 下的历次 spec/review、`HANDOFF.md` |
| **W** | `D:\deepseek harness\workcodex\.tmp\` | 仓库外本地目录 | 2026-11-05 那批「让三个模型写风格标准」的原始答复、合并规范、润色脚本 |
| **M** | `D:\codex\MindForge_MF\english_production\word_pipeline\` | 仓库外本地目录（独立 git，但该子目录从未入库） | 六脉神剑源头 `mnemonic-reference.txt`、任务卡、流水线配置、历史运行产物 |

R 里的文件本来就在 GitHub 上，**不重复复制**，只在 `INVENTORY.md` 里给路径。W 和 M 的文件在仓库外、会随本地清理消失，因此**复制进 `originals/`**。

---

## 3. 关键结论速览

### 3.1 找到多少份原始标准

**原始标准正文 4 份，共 28 条可判定规则：**

| # | 文件 | 内容 | 规则数 |
|---|---|---|---|
| 1 | `originals/methodology/mnemonic-reference.txt` | 六脉神剑 67 行原文：六招定义、选招优先级、记忆三板斧、铁律 1–11、22 字段 schema | 6 招 + 11 条铁律 |
| 2 | `originals/style-standards/reply-A-opus.txt` | Claude Opus 5.5「中文表达标准」A1–A7 | **7 条** |
| 3 | `originals/style-standards/reply-B-opus.txt` | Claude Opus 5.5「可回忆性/记忆设计标准」B1–B5 | **5 条** |
| 4 | `originals/style-standards/reply-C-grok.md` | **Grok「玩梗/口吻/幽默」标准：8 种口吻 + G1–G16** | **16 条 + 8 口吻** |

**配套与派生层（不是原始标准，但同属这套体系）：**

- **配套层**：`task-card.txt`（分镜/可画性层，与六脉神剑并行工作，不是方法论本身）、`task-v6slots.txt`、4 份 `pipeline.config*.json`
- **未采用的残缺稿 3 份**：`reply-A-argon.txt`、`reply-A-argon2.txt`、`reply-A-current.txt` —— 只有任务提问 + 对现有文本的批评，**没有产出任何规则正文**
- **合并层 2 份**：`polish-spec-v1.md`（只有 B）、`polish-spec-v2.md`（A+B 合并，含交叉冲突表）
- **可执行层 1 份**：`polish_v3.py` —— 把 A+B+G 三套合并成一份真 prompt 的脚本，是「三份标准 → 一份 prompt」的唯一物证
- **运行中 Prompt 3 份**：`generate.txt` / `polish.txt` / `judge.txt`

> 计数口径说明：最终报告里说的「**4 份原始标准、28 条风格/玩梗规则**」即上表口径（A7 + B5 + G16 = 28）。六脉神剑的六招与十一条铁律单列，不计入这 28 条。

### 3.2 目前真正参与生成的标准

只有四个来源真正进每一次模型请求：

1. **六脉神剑的方法选择规则**（六招定义 + 选招优先级 + 「没有透明词根不硬凑」+ candy/candle 反例）→ 在 `generate.txt`
2. **用户原话的硬约束机制**（代码 + prompt 双重保障）→ `pipeline.banned_terms()` + `generate.txt` L28–32 + `judge.txt` 第 7 维
3. **Grok 的口吻层**（8 选 1 主口吻、梗密度上限、允许冷梗/荤但不得加血腥）→ `polish.txt` L13–30 + `judge.txt`「像人说话」维
4. **教材腔/废话禁词表**（「该词表示」「用于描述」等）→ `polish.txt` L32–36 + `judge.txt` L38–40

### 3.3 只存在文件里、没被程序使用的标准

- **Grok G1–G16 里与卡片字段绑定的全部条款**（G4 hint 字数、G9 scene 字数、G10 exampleZh 保原句、G12 搭配英文不可动、G13 搭配中文条数/字数、G6–G8 scene 只能写可见物）—— 运行时没有 hint/scene/exampleZh/collocations 这些字段
- **Claude A1（单句≤12 字）与 A5（翻译腔黑名单）的完整版** —— `polish.txt` 只保留了「每行一整句完整的话」和禁词表，**没有** 12 字限制，也没有「进行+名词 / 使得 / 对于…而言 / 通过…来」这套黑名单
- **Claude B 系列的后三条**（B3 可回忆性五勾、B4 场景四层、B5 A/B 换角度）—— 运行时不产出 scene，这三条无用武之地。**注意 B1/B2 并未全丢**：`polish.txt:5`「拆词要拆到能复述」= B1 简写，`:6`「谐音只许一跳」= B2 简写，只是被压缩成一句话、判定细则（首音节声母韵母一致、不涉隐私等）没跟过来
- **记忆三板斧**（具体化 / 新奇化 / 情绪化）—— 在源头 `mnemonic-reference.txt` L20–23，**live `generate.txt` 已删**
- **全卡 22 字段 schema 与 imagePrompt 骨架** —— 只在 MindForge 侧，App 不在用

**一条重要的溯源事实（容易被误读）**：「玩梗 / 幽默 / 口吻」这套方向**不是用户提的**。全库检索找不到用户说过「玩梗、口吻、幽默、梗密度、冷梗、地狱笑话、双关」这些词；这些要求出自项目方 2026-11-05 12:24 写的任务书 `originals/style-standards/brief-C-grok.md`，原文：「要像人写的、要有趣、要会玩梗，**不要像 AI 在写教科书**」「允许黑色幽默 / 地狱笑话」「允许性暗示、双关、荤段子，这是成年人自用」。**Grok 的职责是把它写成可判定条款（G1–G16），不是提出方向。**

同理，G4「hint 每条 2–32 汉字」、G9「scene 50–220 汉字」这两个数字是 **brief 里给的**，与六脉神剑原文的「≤24 字」「100–200 字」直接冲突 —— 冲突的源头在 brief，不在 Grok。详见 `RULE_CONFLICTS.md` 第 2、3 条。

### 3.4 反馈侧的硬事实

134 条真实投票（👍45 / 👎89，踩 66.4%），86 个词。**踩率主要由「方法」决定，不是「文笔」**：谐音 0% 被踩、字形 45.5%、对比 56.2%、词根词缀 71.4%、含义联想 80.5%、一词多义 100%。34 条用户原话里 12 条明确排斥词根词缀，8 条质问「我怎么想到这一截」。而 judge 打分总均 9.17/10 —— **离线评分与真实满意度严重脱节**。

详见 `feedback-samples/feedback-analysis.md`。

### 3.5 ★ 另一条真实在跑、但完全不在上述标准体系里的链路

App 的卡背还有「AI 助记技巧」（`LearningAiPanel.kt:437`），走的是 `util/PromptTemplates.kt:54–68` 里一段硬编码的 Kotlin 字符串：

```
【词根词缀】<一句话词根/词缀拆解>
【联想记忆】<一句话谐音或场景联想>
【复习提示】<一句话记忆抓手>
规则：每段 1 句话，总字数不超过 120 字。只输出上述模板，不要添加解释。
```

它**与上面所有标准都无关**：不读 `prompts/*.txt`、不读历史、不读反馈、不经过 judge、不参与任何统计。而且方向相反 —— 六脉神剑要求「每词主打一招」「没有透明词根不要硬凑」，它却固定要求词根 + 联想**同时给**；主链路为了对抗「为了三段妥协」把字数额度放大到每行 25–60 字，它反而把总量压到 120 字以内。

它**从未出现在任何规格文档里**（不在 `HANDOFF.md` 架构图、不在 `docs/*`），默认 `enabled=false`，需用户自行填 baseUrl/apiKey/modelName 才会跑。**只看 docs 与 rewrite-service 的人会完全看不到它。** 详见 `PROMPT_FLOW.md §8`。

---

## 4. 阅读顺序建议

1. `PROMPT_FLOW.md` —— 先搞清「一次重写到底发生了哪几次模型调用、每次塞进去什么」，后面所有判断都以此为地基
2. `RULE_CONFLICTS.md` —— 标准之间的打架与「文件里有、代码里没有」的落差
3. `INVENTORY.md` —— 逐项台账，需要查某个文件在不在、跑不跑时用
4. `originals/style-standards/reply-C-grok.md` —— Grok 原始标准全文（这是本任务书里点名的重点）
5. `MISSING_MATERIALS.md` —— 明确边界，避免把「没找到」当成「没有」

---

## 5. 安全与脱敏说明（重要）

**本仓库是 PUBLIC 仓库**（`https://github.com/ouquan000/wordhelper` 匿名可访问）。因此归档做了以下处置：

| 项 | 处置 | 原因 |
|---|---|---|
| `~/.dsh/.credentials.yaml` | **未复制** | 真实 API 凭据，仅本机运行时读取（`providers.py:22–24`） |
| `pipeline.config*.json` 的 `python` 字段 | `C:\Users\<原用户名>\…` → `C:\Users\<user>\…` | 本机用户名属设备/账号信息 |
| `pipeline.config*.json` 的 `credential_fingerprints` | 两个 64 位十六进制值 → `<redacted-credential-fingerprint>` | 凭据指纹，虽不可逆，但无分析价值 |
| `wb_key: "sk-wb-local-8788"` | **原样保留** | 本地网关固定占位符，代码注释明确写「不是秘密」（`providers.py:26–27`），且同仓库已公开 |
| `feedback-samples/*.jsonl` | 剔除 `clientId` / `wordId` / `bookId` / `receivedAt` / `method` | `clientId` 虽只是 `f{wordId}-{bookId}-{rating}-r{rev}` 的内容键（**不含设备标识**，见 `RewriteRepository.kt:1089–1096`），但与 `word` 组合可形成更强指纹 |
| `tools/rewrite-service/data/` 整目录 | **未推送**，`.gitignore` 原样保留 | 任务书要求；其中 `queue.json` 的 `clientId` 含 UUID，可串联同一设备行为 |

**敏感信息扫描结果**（对 feedback.jsonl / queue.json / results×62 / service.log 共 65 个文件，18 类模式）：

> API Key、token、cookie、密码、设备序列号、真实账号、手机号、内网 IP、带用户名的绝对路径、身份证、邮箱 —— **全部 0 命中**。日志里连 `C:\`、`http`、`@` 都未出现。34 条用户原话逐条人工核过，无姓名/住址/电话/账号。

**唯一残留的杂项**：`reply-A-opus.txt`、`reply-B-opus.txt`、`reply-A-argon*.txt`、`reply-A-current.txt` 五个文件开头有 Antigravity 客户端侧边栏的抓取噪声，包含几个**其他项目的对话标题**（如「Fixing DeepSeekRuntime Environment」「MindForge 架构评审与收口方案」）。因「原文优先」要求未做删改，此处明确披露；正文起始行见 `originals/style-standards/README.md`。

---

## 6. 局限（请勿越过）

1. **样本不代表大盘**：反馈只在 11-09、11-10、11-19 三天出现，中间 8 天为零；134 条更像少数重度用户的密集试用。
2. **无账号体系**：`clientId` 只能区分设备/请求，无法确认 134 条背后是几个真人，未做「去重到人」的统计。
3. **feedback 与 queue 不是同一子集**：87 vs 66 个词、交集仅 55；`feedback.jsonl` 的 `instruction` 字段 136 条**全为空**（1.4.7.1 前 App 未上传），34 条真实原话来自 `queue.json`。两者不可相加、不可互相归因。
4. **六脉神剑没有更早版本可查**：MindForge 的 `english_production/` 从未进 git（`.gitignore:1` 为 `/*`），仓库内 11 份副本逐字节相同、mtime 均停在 2026-10-14 20:40。**只能证明观测窗口内未变，更早的历史不可考。**
5. **本地文档之间互相矛盾**：本归档只记录事实，不裁定谁对，也不修复（见 `RULE_CONFLICTS.md`）。
6. **第二条链路（App 内 AI 助记）没有任何反馈数据**：134 条投票、26 对 judge 配对、123 条 results **全部只覆盖服务端重写链路**。所以本归档对这些数字能说明的「哪些标准在起作用」，不适用于 AI 助记面板。
7. **没有复习表现数据**：无复习间隔、遗忘曲线、「模糊/不认识」记录。因此本归档**只能**评估「用户当场喜不喜欢这条思路」，**不能**评估「有没有记住」。详见 `MISSING_MATERIALS.md §3.1`。

---

## 7. 本归档**没有**做的事

- 没有改任何一份 Prompt、算法、配置、数据库或 UI
- 没有重新发明、补写或「改进」任何标准
- 没有为了好看而合并多版本，也没有删除看起来已废弃的版本 —— 废弃本身是审查结论之一
- 没有把 8MB 级的批量实验原始文件塞进仓库（只放统计摘要与适用范围，见 `experiments/03-offline-polish-batch-20261105.md`）
- 没有改动 `.gitignore`，没有把 `tools/rewrite-service/data/` 整目录推送出去
- 没有跑任何 A/B、没有训练模型、没有构建或部署 App
