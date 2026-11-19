# 规则冲突 / 重复 / 覆盖关系清单

> **本文件只记录事实，不裁定谁对，也不修复。** 每条都给出可核对的原文位置（`文件:行号`）。
> 行号按 2026-11-19 的文件内容。仓库内路径省略前缀 `D:\deepseek harness\work_anki_enligish\`，仓库外路径写全。

分四类：
- **A. 跨体系冲突** —— MindForge 的整卡标准 vs App 运行时的窄口径标准
- **B. 语言标准内部冲突** —— A/B/G 三套标准合并过程中的自相矛盾
- **C. 文档 vs 实现** —— 文档声称的与代码实际做的不一致
- **D. 重复与覆盖** —— 谁是谁的改名版、谁取代了谁
- **E. 有疑点但证据不足** —— 列出，不下结论

---

## A. 跨体系冲突

### A1. hint 字数：`≤24 字` vs `2–32 汉字`

| 出处 | 原文 |
|---|---|
| `originals/methodology/mnemonic-reference.txt:35` | `⑦ hint 是卡片正面的浓缩短句（2–5 行，**每行 ≤24 字**）` |
| `originals/style-standards/brief-C-grok.md:3` | `hint：2–5 条，**每条 2–32 个汉字**` |
| `originals/style-standards/reply-C-grok.md` G4 | `hint 2–5 条，每条 2–32 汉字` |

**冲突点**：同一个字段，两个上限（24 vs 32）。
**冲突源头不在 Grok**：32 这个数字是 `brief-C-grok.md` 交给 Grok 时就写好的，Grok 只是沿用。也就是「六脉神剑原文 → brief → Grok 准则」这条链上，brief 这一环把 24 改成了 32，且没有说明理由。

### A2. scene 字数：`100–200 字` vs `50–220 汉字`

| 出处 | 原文 |
|---|---|
| `originals/methodology/mnemonic-reference.txt:37` | `⑨ scene 写 **100–200 字**，必须只写画面里有的东西` |
| `originals/style-standards/brief-C-grok.md:4` | `scene：**50–220 个汉字**，必须只描写这张卡的配图里已经画出来的东西` |
| `originals/style-standards/reply-C-grok.md` G9 | `scene 50–220 汉字` |

**冲突点**：区间几乎完全不重叠（100–200 vs 50–220）。
**同 A1**：50–220 也是 brief 给的，不是 Grok 改的。

### A3. derivation 步数：`①②③ 三步` vs `3–5 步`

| 出处 | 原文 |
|---|---|
| `originals/methodology/mnemonic-reference.txt:34` | `⑥ derivation 用 **①②③ 分步**，最后一步必须用「——>」落到词义` |
| `tools/rewrite-service/prompts/generate.txt`（schema 段） | `3–5 行`，`MIN_STEPS, MAX_STEPS = 3, 5`（`pipeline.py:72`） |
| `tools/rewrite-service/prompts/polish.txt:11` | `⑦ 步数自己判断：3 步够就 3 步，链条需要展开就 4 步或 5 步，最多 5 步` |

**冲突点**：原文写死三步，运行时允许 3–5 步。
**这是有意的放宽**（用户提过「像为了三段妥协了很多」），但**没有任何文件把 `mnemonic-reference.txt` 同步更新**，所以源头文件至今仍写「①②③」。

### A4. 字段范围：22 字段 vs 5 字段 vs 1 字段

| 环节 | 可动字段 |
|---|---|
| MindForge 整卡（`mnemonic-reference.txt:41–59`） | 22 个字段，其中 scene/hint/example/exampleZh/collocations/forms/imagePrompt 全部要写 |
| App 生成（`generate.txt` schema） | **5 个**：method / split / elements / derivation / wordRootConfidence |
| App 润色（`polish.txt:1–2`） | **1 个**：derivation |

**冲突点**：`generate.txt` 开篇明说「不写例句、不写配图提示词、不写场景描写 —— 那些环节这个 App 不用」，而它的上游 `mnemonic-reference.txt` 有一半篇幅在讲这些字段。
**后果**：Grok 的 G1–G16 里有 **8 条**（G4/G6/G7/G8/G9/G10/G12/G13）直接绑定 hint/scene/exampleZh/collocations，在 App 里全部无对象可判（详见 `PROMPT_FLOW.md` §4）。

### A5. 记忆三板斧在运行时被删除

| 出处 | 原文 |
|---|---|
| `originals/methodology/mnemonic-reference.txt:20–23` | `记忆三板斧：① 具体化（有人物动作道具细节，能画出来）② 新奇化（越反常越好，允许荒诞）③ 情绪化（有情绪冲击：笑/惊/急/爽）` |
| `tools/rewrite-service/prompts/generate.txt` | **全文无「三板斧 / 具体化 / 新奇化 / 情绪化」** |

**冲突点**：源头把这三条列为 scene 的硬性要求，运行时连字段都没有。
**注意**：这不是「被新标准推翻」，而是「随字段一起消失」。`generate.txt` 里没有任何一句说明「三板斧不再适用」。

---

## B. 语言标准内部冲突

### B1. 「一句一动作」的字数线有四个值并存

| 出处 | 数值 |
|---|---|
| `originals/style-standards/polish-spec-v2.md:78` | 每个逗号段/句号段 **≤12 字** |
| `originals/style-standards/polish_sample.py:33` | scene 允许每段 **≤16 字** |
| `originals/style-standards/polish_sample.py:76` | 校验器 `if len(seg) > 12:` 又按 **12** 报错 |
| `originals/style-standards/polish_v3.py:39` | 提示词写 **12** 个字 |
| `originals/style-standards/polish_v3.py:124` | 校验器 `if cn_len(seg) > 14:` 实际放宽到 **14** |
| `originals/samples/polish-v3-sample.md:57` | 记录 `hint 段过长(13字)` — 证明 14:05 那次按 **12** 判 |

**冲突点**：同一条 A1 规则，16 / 12 / 14 / 12 四个阈值，提示词与校验器还不一致（`polish_v3.py` 自己就是 12 写、14 判）。

### B2. 性 / 荤边界：**从「明令禁止」翻成「明令允许」，且合并版静默删掉了禁令**

| 出处 | 原文 |
|---|---|
| `originals/style-standards/polish-spec-v1.md:61` | `3. 不涉及性、排泄、身体隐私、歧视。` |
| `originals/style-standards/polish-spec-v2.md:54` | `③ 不涉性/排泄/隐私/歧视。` |
| `originals/style-standards/reply-C-grok.md:8` | `允许：冷梗、地狱笑话、**成人性暗示**、双关。必须帮人记住这个词，且不改音、不改义。` |
| `originals/style-standards/reply-C-grok.md:20`（口吻⑧） | `荤：可进 hint、exampleZh、搭配中文。scene 只有在画面已经画出成人身体距离时才可带一句，不得加动作。` |
| `originals/style-standards/polish_v3.py:49` | B2 只剩 `谐音只许一跳：词 → 音 → 义，不许二段谐音。` |

**冲突点**：v1/v2 把「性」列为禁区，Grok 把「成人性暗示/荤」列为允许项，而 `polish_v3.py` 作为「三套合并」的落地实现，**在合并时把 v1/v2 那句禁令直接删了，没有留变更说明**。这是本清单里最实质的一次规则反转 —— 不是措辞差异，是允许清单从「禁」变「许」。

**现行运行态**：`polish.txt:29` 站在 Grok 一侧 —— `冷梗、地狱笑话、成人性暗示、双关，都可以写。` 唯一保留的硬边界是 `polish.txt` L38–43 的「禁血腥/残肢/暴力伤害/未成年/真人/地域族群歧视」。

### B3. 「说人话」到底能不能玩梗

| 出处 | 原文 |
|---|---|
| `originals/style-standards/polish_sample.py:42` | `说人话，可以俏皮，但不要抢戏…**不要用网络烂梗，不要地域、性别、身材、低俗玩笑**。` |
| `tools/rewrite-service/prompts/polish.txt:29` | `冷梗、地狱笑话、成人性暗示、双关，都可以写。` |
| `originals/style-standards/reply-C-grok.md:8` | 同上 |

**冲突点**：11:51 的版本把「低俗玩笑」列为禁区，12:30 之后的版本把「荤/成人性暗示」列为明确允许。两句都自称在讲「说人话」。

### B4. 「不再卡每行字数」vs 实际 prompt 的「每行 40–50 字」

| 出处 | 原文 |
|---|---|
| `docs/1.4.4-交付说明.md:11` | `5. **重新生成的思路更长、更像人话**：3–5 步、**不再卡每行字数**；…` |
| `_recon/acceptance-1.4.4.md:75–78` | 引当时实际 `prompts/polish.txt:10`：`每行 40–50 字`；`prompts/judge.txt:13`：`3–5 步，每步 40–50 字`；`judge.txt:19`：`任何一项 < 8 就 pass=false` |
| 同上 L80 | 自我批评：`把三段枷锁换成四十字枷锁` |

**冲突点**：对外交付说明写「不再卡字数」，同一轮的实际 prompt 里写的是「每行 40–50 字」并且由 judge 一票否决。
**现行状态**：`polish.txt:50` 已改为 `每行是否是一整句人话、把这一步说完整了（**不数字数、不注水**）`；`generate.txt:26` 写 `通常 25–60 字，不按字数卡`。即 40–50 这条**已被现行文本删除**，但**找不到任何一句「废除」声明**，只能由现行文本反推。

### B5. 同一句用户原话，四种「逐字」版本

| 出处 | 引文 |
|---|---|
| `HANDOFF.md:340` | `「字数都可以多一点，我感觉都像是为了三段妥协了很多」` |
| `_recon/acceptance-1.4.4.md:122` | 同 HANDOFF（带逗号） |
| `tools/rewrite-service/prompts/judge.txt:37` | `「你就是字数都可以多一点 我感觉都像是为了三段妥协了很多」`（多「你就是」、少逗号） |
| `tools/rewrite-service/prompts/generate.txt:26` | `学习者明确反馈过「文本太少」「像是为了三段妥协了很多」`（拆成两截） |
| `_recon/acceptance-1.4.4.md:202` | 只剩 `"我感觉都像是为了三段妥协了很多"` |

**冲突点**：四处都声称是原话，不可能同时逐字。**若只取一个，应以 `HANDOFF.md:340` 的带逗号版为准**（它最早、且带完整的「用户：」标注）。

### B6. `polish_sample.py` 自称「按 polish-spec-v2」，实际不是

| 项 | `polish-spec-v2.md` 原定 | `polish_sample.py` 实际 |
|---|---|---|
| A1 句长 | `:78` ≤12 字 | `:33` scene 放宽到 ≤16 |
| A7 | `:111` 字数复核 | `:39` 换成「scene 只写画面里已经有的东西」 |
| B1–B5 | `:44–70` 五条齐全 | 全篇无 |

**冲突点**：文件头 `:2` 写 `按 polish-spec-v2 只改 4 个表达字段`，实际改了 A1、换了 A7、删了 B 系列。

### B7. `polish_batch.py` 自己把 G 派的归属写反

| 行 | 原文 |
|---|---|
| `:21` | `# 两派准则的侧重不同：G 派（grok）偏口吻与梗，A 派（Claude）偏表达与可回忆性。` |
| `:35` | `目标比例 G(claude 侧重梗/grok 派) 4 : A 6` |

**冲突点**：同一文件里 G 派先归 grok，后归 claude。

### B8. 同名「v3」跑的不是同一套规则

- `originals/samples/polish-v3-sample.md:1` 标题写 `润色 v3 样张（2026-11-05 14:05 … Claude+grok 准则合并）`，`:57` 记录 `hint 段过长(13字)` —— 按 **12** 字判。
- `originals/style-standards/polish_v3.py:124`（20:42）校验阈值是 **14**。

**冲突点**：14:05 的「v3」与 20:42 的「v3」判的不是同一套线，同名不同规则。

---

## C. 文档 vs 实现

### C1. `spec-1.4.3.md:117` 声称 `generate.txt` 是「原样复制」，实际早已重写

| 出处 | 原文 |
|---|---|
| `docs/spec-1.4.3.md:117` | `generate.txt  # 六脉神剑（原样，来自 MindForge mnemonic-reference.txt）` |
| `docs/review-1.4.3.md:127` | 记载 `prompts/generate.txt` 原样保留六脉神剑全文（含 scene/hint/example/imagePrompt 及生图指令） |
| `docs/review-1.4.3-round3.md:154` | 指出实际已重写为 3.5KB 定制提示词，不再原样 |

**事实**：现行 `generate.txt` 45 行 / 4888 B，只保留六招定义、选招优先级、铁律子集与 5 字段 schema，**scene/hint/example/imagePrompt/三板斧/22 字段 schema 全部不在**。`review-1.4.3.md:127` 的描述在当时是否成立已不可考，但**对现行文件肯定不成立**。

### C2. judge 维度名的三种写法

| 出处 | 写法 |
|---|---|
| `pipeline.py:30` | `"像人说话"` |
| `pipeline.py:33–40` `JUDGE_ALIASES` | `"像不像人说话": "像人说话"` |
| `HANDOFF.md:680` | `像人谐话→像人说话` |
| `_recon/1.4.7.1/acceptance-report.md:373` | 实际日志里出现 `"像人谐话":8` |
| `pipeline.py:44` `_canonical_dim` | 用 `difflib` 近似匹配（阈值 0.6）兜底 |

**冲突点**：同一评委维度有「像人说话 / 像不像人说话 / 像人谐话」三种表述，靠别名表和模糊匹配兜住。另：`results/*.json` 里还出现过 `链条闭合` 这一维（10 条样本），同样靠 `JUDGE_ALIASES` 归一到「逻辑连贯」。

### C3. judge 均分 9.17 与 66.4% 踩率的背离

| 来源 | 数字 |
|---|---|
| `feedback-samples/feedback-analysis.md` §5.2 | 8 维 723 个分值，总均 **9.174 / 10**，最低 6 分 |
| 同上 §0 | 真实投票 134 条，**down 89（66.4%）** |

**冲突点**：同一批卡，离线评委平均给 9 分以上，真实用户 2/3 点踩。**不建议把 judge 分数当唯一上线门槛**（这也解释了 `POLISH` 判分偏松：谐音维 9.86 分几乎不会失分）。

**更强的一层证据（本次新做，见 `experiments/01-judge-vs-feedback-calibration.md`）**：把 `results` 与 `feedback` 按 `(word, rev)` 对齐，得到 26 对「同一版本的 judge 分 ↔ 用户投票」：

| 投票 | 配对数 | judge 均分 |
|---|---|---|
| 👍 | 7 | 9.123 |
| 👎 | 19 | 9.093 |

**差 0.03 分。** 而且被用户点踩的那 19 个版本里，**18 个照样是「全维 ≥8 通过」**（94.7%）。所以问题不只是「分偏松」，而是**这个分和用户满意度之间没有可观测的区分力**。（n=26，样本小、有偏，见实验文档 §5 的适用边界。）

### C4. 审计 README 自身的一处不准确（已在归档时修正）

初稿曾写「Claude B1–B5 一条都没有」，实际 `polish.txt:5` `① 拆词要拆到能复述` = B1 简版、`:6` `② 谐音只许一跳` = B2 简版。已改为「B1/B2 以简版进入，B3–B5 未进」。**保留此条是为了说明：连本次审计的第一版结论也被现实验证过一遍才修正。**

---

## D. 重复与覆盖

### D1. `polish-spec-v1.md` 的 R1–R5 就是 v2 的 B1–B5（同一份东西改名）

- `polish-spec-v1.md:1` 标题 `表达层润色规范 v1`，`:4` 写明上游只有 `reply-B-opus.txt`，中文表达标准「待 Gemini 4 Argon 补位」。
- `polish-spec-v2.md:1` 标题 `v2（合并版）`，`:5–6` 写明 B 来自 `reply-B-opus.txt`、A 来自 `reply-A-opus.txt`。
- v1 的 `R1–R5`（`:49–91`）在 v2 变成 `B1–B5`（`:44–70`）。

**结论**：v1 是「只有 B」的半成品，v2 是「A+B」的合并版；v1 被 v2 完全覆盖，没有独立内容。

### D2. 三份 `reply-A-*` 未产出规则，被 `reply-A-opus.txt` 取代

| 文件 | 行数 | 是否含 A1–A7 规则正文 |
|---|---|---|
| `reply-A-argon.txt` | 87 | **否**（只有任务提问 + 对现有文本的 3 行批评） |
| `reply-A-argon2.txt` | 104 | **否** |
| `reply-A-current.txt` | 94 | **否** |
| `reply-A-opus.txt` | 113 | **是**（`:89` 起 A1–A7） |

**原因（有记载）**：原定评审模型 Gemini 4 Argon 因 `User location is not supported for the API use.`（区域限制）不可用，改由 Claude Opus 5.5 写。三份 argon/current 是失败尝试的抓取残留。

### D3. A1 的「每段 ≤12 字」被 1.4.4 主动删除

- `_recon/acceptance-1.4.4.md:136`：`先说改对了的（这些是实打实的进步，别回退）：polish.txt 删掉了"每个逗号段不超过 12 个字"；…`
- 现行 `polish.txt` 无 12 字限制。

**这是全库唯一一处明确承认「旧标准被删除」的记载。**

### D4. 早期判死的样本被「现行更宽的规则」复活

- `originals/style-standards/polish_audit.py:2–5`：`润色收尾审计：1) 用最终版规则把全程被判不合格的条目重判，能救的直接落盘；2) 全量复查已落盘成品是否仍合规（**防早期规则漏放**）…`
- 同文件 `:36`：`"""把历史上被判不合格、但按现行规则其实合格的样本落盘。"""`

**结论**：规则确实变宽过，且旧判定被系统性覆盖 —— 这本身是「标准发生过实质变更」的证据。

### D5. Grok 的归因只到「类别级」，没有逐条署名

明确写 Grok 的原文只有这些：

- `originals/style-standards/polish_v3.py:2`：`润色 v3：Claude 两份准则（中文表达 A / 可回忆性 B）+ grok 那份（G 规则、口吻分层、硬边界）合并后交 ds4.1-flash 执行。`
- `polish_v3.py:195` 生成标题 `Claude+grok 准则合并`
- `originals/style-standards/polish_batch.py:21`：`G 派（grok）偏口吻与梗，A 派（Claude）偏表达与可回忆性。`
- `polish_batch.py:97` / `polish_audit.py:54`：`"spec": "claude-A7+B7 + grok-G16"`
- `originals/samples/polish-v3-sample.md:1`：`Claude+grok 准则合并`

**没有**任何文档逐条列出「G1 来自 Grok、G2 来自 Grok……」。`reply-C-grok.md` 整份是 Grok 产出，可作为逐条来源，但项目侧从未做对照表。

**另需纠正一个常见误记**：Grok 原文写的是 G2「hint 要像人记词，不像教材」，**不是**「像人说话」。「像人说话」这五个字在 2026-11-05 的三份规范里都不存在，现存最早带该 exact 词的文件是 `_recon/1.4.7/fb-exp/rewrite-service/prompts/judge.txt:15`（2026-11-09 20:49）。

---

## E. 有疑点但证据不足（不下结论）

1. **「玩梗标准是否被废弃」** —— 没有任何文件承认废弃。相反，现行 `polish.txt:13–30` 与 `judge.txt:38–40` 仍保留八口吻 + 密度 + 「像人说话」维度。能确认的只是**执行产物没体现**：`polish-v3-sample.md`（5 词）与 `polish-audit-sample.md`（20 词）通读均未见明显梗/双关/荤段子。这是标准与产物的落差，不是文档自认废弃。

2. **`HANDOFF.md:342` 的「叠回」暗示曾经删过** —— 原文 `polish 叠回 8 种口吻、梗密度上限…`。「叠回」意味着 1.4.4 之前曾有一版没有这些；但**找不到哪一版删过、为什么删**。这是版本谱系的空白。

3. **`polish-spec-v2.md:7` 的「5198 张卡」vs `v1:16` 的「5183 张 A 图」** —— 两个数字口径不同（卡 vs 图），未见任何解释。

4. **1.4.4 当时的 `prompts/judge.txt` / `polish.txt` 原件未保留** —— 只有 `_recon/acceptance-1.4.4.md:75–78,136` 的引用。所以「1.4.4 引入了 40–50 字」只能确认存在过，无法给出原件行号。

5. **`review-1.4.3.md:127` 说 generate.txt「原样保留六脉神剑全文」** —— 在 1.4.3 当时可能成立（当时或许是原样复制的），但那个版本的文件已不在仓库里；现行文件肯定不是原样。**两句话可能都对，只是说的不是同一个文件版本** —— 这一点无法证实，因为 1.4.3 的 prompt 原件没有留档。

---

## F. ★ 同一个 App 里并存两套互不相容的「助记」标准

> 这一节是归档后期才发现的，**最容易漏**：除了 `tools/rewrite-service` 的三份 prompt，App 自己还有一条独立的助记生成链路，走的是**另一套完全不同的标准**。

### F1. 事实

| | 链路 1：服务端重写 | 链路 2：App 内「AI 助记技巧」 |
|---|---|---|
| 入口 | 卡背「重写」按钮 | 卡背「AI 助记技巧」按钮 |
| 代码 | `tools/rewrite-service/pipeline.py` | `app/src_repo/…/data/repository/AIRepository.kt` |
| Prompt | `prompts/generate.txt` + `polish.txt` + `judge.txt`（三份，走三步） | `util/PromptTemplates.kt:54–68 buildMemoryAidPrompt()`（**一份，一步**） |
| 输出结构 | `method / split / elements / derivation`（**3–5 步推导**） | 三段固定标签：`【词根词缀】` `【联想记忆】` `【复习提示】` |
| 字数 | 每行 25–60 字、3–5 行 | **每段 1 句话，总字数 ≤120 字** |
| 方法观 | 六脉神剑：**每词主打一招**，「没有透明词根不要硬凑」 | **固定先给词根词缀，再给联想** —— 等于每张卡都同时给词根 + 联想 |
| 口吻要求 | 有（八口吻、梗密度、禁教材腔） | **完全没有** |
| 评分/复核 | 有（judge 七维 + 两轮 + 禁令扫描） | **没有**（只有 `AIContentFormatter` 的格式兜底） |
| 模型 | `ds-v41-flash` 等六个（服务端） | 用户自填的 OpenAI 兼容接口，默认 `gpt-3.5-turbo`，`temperature = 0f` |
| 历史/反馈 | 读 `history.build()` | **不读**任何历史或反馈 |
| 结果去向 | `data/results` → 回写 `tb_word_memory` | 只进本地 `AICache` |

### F2. 冲突点（逐条）

1. **「每词主打一招」vs「词根 + 联想同时给」** —— 六脉神剑的核心是「只选一个主招」，因为同时给多招会稀释记忆钩。链路 2 的模板**结构性要求三段全给**，与这条原则直接相反。
2. **「没有透明词根不要硬凑」vs「固定输出词根词缀」** —— `generate.txt:15–16` 明确禁止硬凑词根；链路 2 的模板第一个标签就是【词根词缀】，对没有词根的词（如 `candy`、`sound` 类）等于强制硬凑。
3. **「3–5 步、每行 25–60 字」vs「每段 1 句、总共 ≤120 字」** —— 后者恰好是 `generate.txt:26` 明确反对的形态：「学习者明确反馈过『文本太少』…不许为了凑三行砍内容」。链路 2 把 120 字当成硬上限。
4. **口吻标准完全缺席** —— 八口吻、梗密度、禁教材腔这三样，在链路 2 的 prompt 里一个字都没有；而 `PromptTemplates.kt:13` 的 system 反而是「内容简短、可直接复习，不得输出多余段落」，与「要像人说话、可以玩梗」方向相反。
5. **两套的产物都出现在同一个「卡背」上，用户分不清哪条来自哪套** —— 一条是重写服务写进 `tb_word_memory` 的 `approach`，另一条是 AI 面板缓存。二者没有共同的评分、没有说话人标记。

### F3. 归属与现状

- 链路 2 的模板**没有任何文档说明它属于哪套标准**：不在 `HANDOFF.md` 的架构图里（§3 只画了重写链路），不在 `docs/*` 的任何规格里，也没有版本记录。
- 它是**活的**：`AIRepository.kt:128` 与 `:227` 是真实调用点，`AIConfig` 里 `enabled` 默认为 `false`，需要用户在设置里填了 baseUrl/apiKey/modelName 才会跑（`validateConfig(config, requireEnabled = true)`）。
- **不参与任何统计**：`feedback.jsonl` / `results/` / `judge` 都与它无关。所以本归档里的 134 条反馈，**全部只关于链路 1**。

### F4. 为什么这条要写进「冲突」

任务书要求「查明这些标准究竟存在于哪些文件、哪些被正式加载、哪些只是文档」。链路 2 是这个问题的**反例**：一套从未被写进任何规范文档的助记标准，**却真实地在跑**，而且它的方法观与主链路相反。GPT 审查时若只看 `docs/` 与 `tools/rewrite-service/`，会完全看不到它。
