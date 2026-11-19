# INVENTORY — 逐项资料台账

> 每一项都给出：资料名 / 原路径 / 归档路径 / 用途 / **当前是否参与运行** / 版本与日期。
> 「参与运行」只有三种状态：
> - **RUNNING** — 每次真实请求都会被读取或执行。
> - **REPO-LIVE（未读）** — 文件在仓库里、在运行目录下，但运行时不读。
> - **ARCHIVED** — 只在归档/文档层，不参与任何运行。
>
> 仓库内路径省略前缀 `D:\deepseek harness\work_anki_enligish\`。

---

## 0. 一页速览

| 分类 | 份数 | 参与运行 |
|---|---|---|
| A. 方法体系原文（六脉神剑） | 1 | 部分（只剩方法与选招规则，见 §1.1） |
| B. 语言/幽默标准原文 | 3（A / B / G 三份） | 部分（见 §1.2） |
| C. 合并与派生规范 | 3（spec-v1 / spec-v2 / polish_v3.py） | 否 |
| D. 正在运行的 prompt | 3（generate / polish / judge） | **是** |
| E. 运行时代码（决定标准怎么被用） | 8 | **是** |
| F. 归档的外部原件 | **27 个文件** | — |
| G. 数据与样本 | feedback.jsonl 136 条 / queue 158 项 / results 123 条 | 间接 |

---

## 1. 真正参与运行的（RUNNING）

### 1.1 Prompt 文件（三份，每次请求整份发送）

| 资料名 | 原路径 | 归档路径 | 用途 | 参与运行 | 版本/日期 |
|---|---|---|---|---|---|
| 生成提示词 · 六脉神剑（运行时版） | `tools/rewrite-service/prompts/generate.txt` | 未复制（已在仓库） | P1 生成 3–5 步推导 | **RUNNING**（system，每次现读） | 45 行 / 4888 B / 2026-11-09 23:57 |
| 润色提示词 | `tools/rewrite-service/prompts/polish.txt` | 未复制（已在仓库） | P2 只改 derivation 措辞 | **RUNNING** | 58 行 / 4326 B / 2026-11-08 15:26 |
| 评委提示词 | `tools/rewrite-service/prompts/judge.txt` | 未复制（已在仓库） | P3 七维打分 + 三旗标 | **RUNNING** | 87 行 / 9285 B / 2026-11-11 05:21 |

### 1.2 语言标准在运行中的实际落点

| 标准 | 出处原件 | 在运行中的形态 | 参与运行 |
|---|---|---|---|
| 八种口吻（冷幽默/损友/正经人跑偏/双关/带体温/梗王/毒舌/荤） | `reply-C-grok.md` L11–20 | `polish.txt:13–22` 压缩成「每种一句」，默认口吻「带体温」 | **RUNNING（压缩版）** |
| 梗密度上限 | `reply-C-grok.md` G16 / L54 | `polish.txt:24–26` + `judge.txt:38–40` 门槛 | **RUNNING** |
| 允许冷梗/地狱笑话/成人暗示/双关（不改音不改义） | `reply-C-grok.md:8` | `polish.txt:28–30` | **RUNNING** |
| 教材腔禁词（该词表示/用于描述/本词指/意为/指的是/通常用来） | `reply-C-grok.md` G2 | `polish.txt:32–36` + `judge.txt:38–40` | **RUNNING** |
| 正确的废话黑名单 | `reply-A-opus.txt` A3 | `polish.txt:32–36`（部分）+ `polish.txt:8` | **RUNNING（部分）** |
| B1 拆词粒度 | `reply-B-opus.txt` B1 | `polish.txt:5`「拆词要拆到能复述」 | **RUNNING（一句话）** |
| B2 谐音只许一跳 | `reply-B-opus.txt` B2 | `polish.txt:6`「谐音只许一跳」 | **RUNNING（一句话）** |
| A2 删可删修饰 | `reply-A-opus.txt` A2 | `polish.txt:7` | **RUNNING（一句话）** |
| 六脉神剑六招与选招优先级 | `mnemonic-reference.txt` L5–16 | `generate.txt:5–16`（重写版，非原文） | **RUNNING** |
| 谐音必须过音标关 | `mnemonic-reference.txt` L25 | `generate.txt:21` + `judge.txt` 第 3 维 | **RUNNING** |
| 元素划分必须是真实词素 | `mnemonic-reference.txt` L29 | `generate.txt:25` + `judge.txt` 硬红线 | **RUNNING** |
| 末步必须「——>」落到词义 | `mnemonic-reference.txt` L34 | `generate.txt:26` + `polish.txt:9` + `pipeline.normalize()` 硬校验 | **RUNNING（有代码强制）** |
| 用户原话优先级最高 | ——（来自 feedback/队列原话） | `generate.txt:28–32` + `pipeline.banned_terms()` 一票否决 | **RUNNING** |

### 1.3 运行时代码

| 资料名 | 原路径 | 用途 | 参与运行 | 版本/日期 |
|---|---|---|---|---|
| 生成/润色/评分管线 | `tools/rewrite-service/pipeline.py` | `_brief`/`_judge_brief` 拼 user；`normalize` 校验 3–5 步；`banned_terms` 抽禁令；`run` 两轮循环 | **RUNNING** | 938 行 / 40619 B / 2026-11-12 00:13 |
| 同词历史派生 | `tools/rewrite-service/history.py` | 把点踩/点赞/原话摘成四段进 user | **RUNNING** | 169 行 / 6692 B / 2026-11-11 05:23 |
| 模型与网关适配 | `tools/rewrite-service/providers.py` | 六个模型、`judge_target` 换模型、WB 忽略 system | **RUNNING** | 308 行 / 10941 B / 2026-11-10 22:48 |
| HTTP 服务与 worker | `tools/rewrite-service/service.py` | 队列、并发 2、路由、反馈落盘 | **RUNNING** | 377 行 / 14402 B / 2026-11-11 05:31 |
| 队列与结果存储 | `tools/rewrite-service/store.py` | queue.json / results / feedback.jsonl | **RUNNING** | 36712 B / 2026-11-11 05:49 |
| 卡背反馈实体 | `app/src_repo/app/src/main/java/com/kaoyan/wordhelper/data/entity/RewriteFeedback.kt` | tb_feedback，DB19 带 approach_rev | **RUNNING** | 2264 B / 2026-11-09 09:29 |
| 重写入口 UI | `…/ui/screen/RewriteBar.kt` | 多行输入（200 字）+ 提交/撤销/停止 | **RUNNING** | 26141 B / 2026-11-18 01:53 |
| 重写仓库 | `…/data/repository/RewriteRepository.kt` | 入队、同步、applyResult 三层守卫、反馈推送 | **RUNNING** | — |

### 1.4 ★ 第二条生成链路：App 内「AI 助记技巧」

**这是本次审计里最容易被漏掉的一件事**：除了 rewrite-service 之外，App 自己还有一套**完全独立**的助记生成，走用户自填的 OpenAI 兼容接口。

| 资料名 | 原路径 | 用途 | 参与运行 | 版本/日期 |
|---|---|---|---|---|
| 提示词模板 | `app/src_repo/app/src/main/java/com/kaoyan/wordhelper/util/PromptTemplates.kt` | `buildMemoryAidPrompt()` 生成【词根词缀】【联想记忆】【复习提示】三段，每段 1 句，**总字数 ≤120 字** | **RUNNING**（`AIRepository.kt:128`） | 98 行 |
| 输出格式化与兜底 | `app/src_repo/…/util/AIContentFormatter.kt` | `normalizeMemoryAid()`：缺「【词根词缀】/【联想记忆】」标签就按行硬拆成三段 | **RUNNING**（`AIRepository.kt:130`） | 135 行 |
| AI 调用仓库 | `app/src_repo/…/data/repository/AIRepository.kt` | `AIConfig{enabled, apiBaseUrl, apiKey, modelName}`，默认 `gpt-3.5-turbo`，`temperature = 0f`（`type != null` 时） | **RUNNING** | — |
| AI 面板 UI | `app/src_repo/…/ui/screen/LearningAiPanel.kt` | 「AI 助记技巧」按钮与结果展示 | **RUNNING** | — |

**它和主链路的关系**：**没有任何关系**。不读 `prompts/*.txt`、不读历史、不读反馈、不经过 judge、结果只进本地 `AICache`。它的助记标准是「词根词缀 + 联想记忆 + 复习提示」三段式 ≤120 字，与六脉神剑「每词主打一招、3–5 步、每行 25–60 字」**是两套不相容的标准**（详见 `RULE_CONFLICTS.md` C 组）。

---

## 2. 归档进 `originals/` 的 27 个外部原件（ARCHIVED）

> 这些文件在仓库外，**已逐字节复制**（字节数、mtime 与源一致）。只做了两处必要的脱敏：4 份 `pipeline.config*.json` 里的本机用户名与两个凭据指纹。

### 2.1 `originals/methodology/` — 方法体系（7 份）

| 资料名 | 原路径 | 归档路径 | 用途 | 参与运行 | 版本/日期 |
|---|---|---|---|---|---|
| **六脉神剑原文（67 行）** | `D:\codex\MindForge_MF\english_production\word_pipeline\prompts\mnemonic-reference.txt` | `originals/methodology/mnemonic-reference.txt` | 六招定义/选招优先级/铁律 11 条/22 字段 schema/imagePrompt 骨架 | **否**（被 `generate.txt` 重写版取代） | 6026 B / 2026-10-14 20:40 |
| 整卡任务书 | `…\prompts\task-card.txt` | `…/task-card.txt` | MindForge 侧整卡生成任务书（分镜/可画性） | 否（本 App 不读） | 9062 B / 2026-10-20 12:21 |
| v6 槽位任务书 | `…\prompts\task-v6slots.txt` | `…/task-v6slots.txt` | MindForge v6 分镜槽位 | 否 | 1302 B / 2026-10-16 00:49 |
| 流水线配置（现行） | `…\pipeline.config.json` | `…/pipeline.config.json` | 模型/步骤/去噪参数 | 否 | 971 B / 2026-10-21 15:21（脱敏后） |
| 配置快照 ×3 | `…\pipeline.config.json.bak-*` | `…/pipeline.config.bak-*.json` | 四版配置演进证据 | 否 | 788–972 B / 2026-10-17 ~ 10-20 |

### 2.2 `originals/style-standards/` — 语言与幽默标准（17 份）

| 资料名 | 原路径 | 归档路径 | 用途 | 参与运行 | 版本/日期 |
|---|---|---|---|---|---|
| **需求 brief · A（中文表达）** | `D:\deepseek harness\workcodex\.tmp\brief-A-4argon.md` | `originals/style-standards/brief-A-4argon.md` | 交给模型的提问稿（A 方向） | 否 | 2422 B / 2026-11-05 00:58 |
| **需求 brief · B（可回忆性）** | `…\brief-B-opus.md` | `…/brief-B-opus.md` | 提问稿（B 方向） | 否 | 4692 B / 2026-11-05 00:59 |
| **需求 brief · C（玩梗）★** | `…\brief-C-grok.md` | `…/brief-C-grok.md` | **最早成文的玩梗要求**（八口吻+密度+五条调性） | 否 | 4129 B / 2026-11-05 12:24 |
| bit 卡参考样本 | `…\brief-card-bit.json` | `…/brief-card-bit.json` | 提问时附的真实卡 | 否 | 4904 B / 2026-11-05 00:58 |
| **A 标准正式稿（A1–A7）** | `…\reply-A-opus.txt` | `…/reply-A-opus.txt` | Claude 产出的七条中文表达标准 | 部分（A2/A3 一句话） | 6791 B / 2026-11-05 01:20 |
| **B 标准正式稿（B1–B5）** | `…\reply-B-opus.txt` | `…/reply-B-opus.txt` | Claude 产出的五条记忆设计标准 | 部分（B1/B2 一句话） | 7891 B / 2026-11-05 01:02 |
| **G 标准正式稿（G1–G16）★** | `…\reply-C-grok.md` | `…/reply-C-grok.md` | **Grok 写成的八口吻 + 16 条可判定规则 + 10 条自检** | 部分（口吻/密度/禁词） | 7681 B / 2026-11-05 12:30 |
| 残缺稿 ×3 | `…\reply-A-argon.txt` / `reply-A-argon2.txt` / `reply-A-current.txt` | 同名 | 只有提问稿+对现状的三行批评，**没有规则正文** | 否（未采用） | 3574/3907/3934 B / 2026-11-05 01:02–01:11 |
| 合并规范 v1 | `…\polish-spec-v1.md` | `…/polish-spec-v1.md` | 只有 B 的 R1–R5；第一份禁词表 | 否（被 v2 完全覆盖） | 7515 B / 2026-11-05 01:07 |
| 合并规范 v2 | `…\polish-spec-v2.md` | `…/polish-spec-v2.md` | A+B 合并 + 交叉冲突表 + 红黄绿判定 + 程序断言 | **否**（人读规范，代码不读） | 9939 B / 2026-11-05 01:20 |
| 三合一脚本 | `…\polish_v3.py` | `…/polish_v3.py` | A7+B7+G16 合并成 prompt 的离线脚本 | 否（离线） | 11460 B / 2026-11-05 20:42 |
| 批量润色 | `…\polish_batch.py` | `…/polish_batch.py` | A 派/G 派双 prompt 批量跑 | 否 | 8235 B / 2026-11-05 16:51 |
| 导出 | `…\polish_export.py` | `…/polish_export.py` | 导出回卡片 | 否 | 4682 B / 2026-11-05 20:45 |
| 审计重判 | `…\polish_audit.py` | `…/polish_audit.py` | 用最终规则重判历史样本 | 否 | 5111 B / 2026-11-05 18:01 |
| 取样 | `…\polish_sample.py` | `…/polish_sample.py` | 从 MindForge 产物取样 | 否 | 6381 B / 2026-11-05 11:51 |

### 2.3 `originals/samples/` — 正反样本（3 份）

| 资料名 | 原路径 | 归档路径 | 用途 | 参与运行 | 版本/日期 |
|---|---|---|---|---|---|
| 早期 clean 样张 | `…\polish-sample-clean.md` | `originals/samples/polish-sample-clean.md` | 按 v2 改的对照样张 | 否 | 7375 B / 2026-11-05 11:53 |
| v3 五词样张 | `…\polish-v3-sample.md` | `…/polish-v3-sample.md` | A/B/G 合并后的样张（**通读无梗**） | 否 | 8007 B / 2026-11-05 14:05 |
| 20 词审计样张 | `…\polish-audit-sample.md` | `…/polish-audit-sample.md` | 用最终规则重判的 20 词 | 否 | 31741 B / 2026-11-05 20:43 |

**未复制的大文件**（只做统计，不入库）：`polish-batch.jsonl`（8.4 MB）、`polish-batch-20261105-2036-run2.json`（8.1 MB）、各 `polish-*.log`。

---

## 3. 仓库内相关文档（REPO-LIVE，不复制）

| 资料名 | 原路径 | 用途 | 参与运行 |
|---|---|---|---|
| 总交接文档 | `HANDOFF.md`（57873 B / 2026-11-16 00:58） | 架构、模型、§12.1 提示词放宽记录、§9 个性化生成计划 | 否（人读） |
| 1.4.3 规格 | `docs/spec-1.4.3.md`（12496 B / 2026-11-07 15:08） | 服务设计；`:117` 声称 generate.txt「原样来自 mnemonic-reference.txt」（**与实际不符**） | 否 |
| 1.4.3 评审 | `docs/review-1.4.3.md`（19547 B / 2026-11-07 11:26） | 指出原样保留导致多烧 token，建议裁 `generate-derivation.txt` | 否 |
| 1.4.3 三轮评审 | `docs/review-1.4.3-round3.md`（18201 B / 2026-11-07 13:34） | `:154` 指出「原样复制」说法已不成立 | 否 |
| 1.4.4 验收（自审） | `_recon/acceptance-1.4.4.md`（25721 B / 2026-11-08 15:18） | `:73–80` 承认「三段妥协」换成「每行 40–50 字」新枷锁；`:136` **唯一**明确记载「删掉 12 字规则」 | 否 |
| 1.4.4 交付说明 | `docs/1.4.4-交付说明.md`（2205 B / 2026-11-08 15:58） | `:11` 称「不再卡每行字数」（与 acceptance 冲突） | 否 |
| 决策记录 | `docs/decisions-1.4.7.1.md` / `decisions-1.4.7.2.md` | 1.4.7 阶段的取舍 | 否 |
| 其他 | `docs/review-1.4.3-round2.md`、`review-1.4.3.1.md`、`spec-1.4.3.1.md`、`spec-1.4.3.2.md`、`test-report-*.md`、`project-brief-for-gpt.md`、`review/REVIEW-BRIEF.md`、`review/README.md` | 同期设计/验收证据 | 否 |

---

## 4. 数据与样本

| 资料名 | 原路径 | 归档路径 | 用途 | 参与运行 | 版本/日期 |
|---|---|---|---|---|---|
| 反馈原始库 | `tools/rewrite-service/data/feedback.jsonl` | **未入库**（被 `.gitignore` 整目录忽略） | 136 条 👍/👎 | **RUNNING**（经 history.build） | 111432 B / 2026-11-19 17:09 |
| 反馈匿名样本 | 由上一行派生 | `feedback-samples/feedback-samples-anon.jsonl` | 134 条真实反馈，剥掉 wordId/bookId/clientId/approachRev | 否 | 134 行 / 97677 B |
| 反馈分析报告 | 新增 | `feedback-samples/feedback-analysis.md` | 字段说明/踩率/方法对比/judge 脱节/敏感扫描 | 否 | 308 行 / 25995 B |
| 重写队列 | `…/data/queue.json` | **未入库** | 158 项，含 **37 条非空用户原话** | **RUNNING**（原话经 history 进 prompt） | 192754 B |
| 结果库 | `…/data/results/*.json` | **未入库** | 62 词 123 条成品（含 scores/attempts） | 读侧（`/rewrite/results`） | 209606 B |
| 服务日志 | `…/data/logs/service.log` | **未入库** | 运行证据 | 否 | 109008 B |

**为什么 data/ 不整目录推送**：它被 `.gitignore` 明确忽略；且含运行态队列与日志。按任务要求，只把「去凭据、去无关信息」的样本以独立 JSONL 放进 `feedback-samples/`，并保持 `approach`/`method`/`rev`/`createdAt` 等分析必需字段。

---

## 5. 明确未入库的东西（及原因）

| 项目 | 原因 |
|---|---|
| `~/.dsh/.credentials.yaml` 及各 provider 真实 API Key | **禁止上传**。`providers.py:22–24` 从该文件运行时现读，仓库里没有任何凭据。 |
| `tools/rewrite-service/data/` 整目录 | `.gitignore` 已忽略；且含运行态数据与日志。 |
| 8 MB 级 `polish-batch*.json(l)` 与 `.log` | 体积过大且是中间产物；已在本文档登记来源与用途。 |
| `D:\codex\MindForge_MF` 的 `word-runs/` 全量产物（数千目录） | 只挑 3 张代表性卡（root/sound/contrast）在 `RULE_CONFLICTS.md` 与 `feedback-analysis.md` 中举例，不整目录搬运。 |
| `v1.4.7v6/` 未跟踪目录 | 与本任务无关，未触碰。 |
| App 的 `assets/cet6_cards.json`（83867+ 行） | 是成品数据不是标准；只在检索「场景联想」时作为出现位置记录。 |

---

## 6. 计数口径（给最终报告用）

- **原始标准文件**：4 份 —— 六脉神剑原文 1 份（`mnemonic-reference.txt`）+ 语言标准 3 份（`reply-A-opus` / `reply-B-opus` / `reply-C-grok`）。
- **语言/幽默/口吻规则条数**：**28 条** —— A 系列 7 条 + B 系列 5 条 + G 系列 16 条。
- **另有派生/合并规范 3 份**（`polish-spec-v1` / `polish-spec-v2` / `polish_v3.py` 内嵌 prompt），它们是「合并产物」不是「新原始标准」。
- **正在运行的标准**：3 份 prompt + 1 份代码内的禁令抽取 + 1 份历史摘要；语言侧只有口吻分层/梗密度/禁词表 + B1/B2/A2/A3 各一句。
- **完全没被程序读过的**：`mnemonic-reference.txt`、`task-card.txt`、`task-v6slots.txt`、4 份 config、`polish-spec-v1/v2`、`polish_v3.py` 及 4 个离线脚本、3 份残缺 reply-A-*、3 份样张。
