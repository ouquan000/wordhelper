# originals/style-standards — 语言表达 / 幽默 / 口吻标准（原文，未删改）

本目录的文件全部是从原路径**逐字节复制**的副本，没有改写、精简、合并或重排。
唯一的例外见 `../../README.md §5`（配置类文件的三处凭据打码，且不在本目录）。

## 来源与时间

全部来自仓库外本地目录 `D:\deepseek harness\workcodex\.tmp\`（代号 **W**），
是 2026-11-05 那一天「让三个模型分别写一份标准」的完整物证链。

## 文件清单与正文起始行

前五个 `reply-A-*` / `reply-B-*` 文件是**从 Antigravity 客户端界面抓取**的文本，
开头带有侧边栏噪声（「Antigravity / 工作区 / 视图 / 窗口 / 新建对话 …」以及几个**其他项目**的对话标题）。
按「原文优先」原则**原样保留未删**，阅读时请从下表标注的行开始。

| 文件 | 行数 | 正文起 | 说明 |
|---|---|---|---|
| `brief-A-4argon.md` | 38 | L3 | 交给 Gemini 4 Argon 的中文表达任务书（该次调用因区域限制失败，见下） |
| `brief-B-opus.md` | 59 | L5 | 交给 Claude Opus 的可回忆性任务书 |
| `brief-C-grok.md` | 60 | L10 | **交给 Grok 的玩梗/口吻任务书 —— 「像人写的、要会玩梗」方向的最早成文出处** |
| `brief-card-bit.json` | — | L1 | 作为参考样本的真实卡片（bit），JSON |
| `reply-A-argon.txt` | 87 | **L32** | Argon 的答复：**只有任务提问 + 对现状文本的三行批评，没有规则正文** → 未采用 |
| `reply-A-argon2.txt` | 104 | **L37** | 同上，第二次尝试，同样没有产出规则 |
| `reply-A-current.txt` | 94 | **L37** | 同上，第三次尝试，同样没有产出规则 |
| `reply-A-opus.txt` | 113 | **L49** | Claude Opus 5.5 的答复：**A1–A7 中文表达标准正文** |
| `reply-B-opus.txt` | 113 | **L35** | Claude Opus 5.5 的答复：**B1–B5 可回忆性标准正文** |
| `reply-C-grok.md` | 87 | L1 | **Grok 的答复：8 种口吻 + G1–G16 正文**，无界面噪声 |
| `polish-spec-v1.md` | 167 | L1 | 合并规范 v1（只有 B 系列，A 系列待补） |
| `polish-spec-v2.md` | 209 | L1 | 合并规范 v2（A+B 合并，含两套标准的交叉冲突表） |
| `polish_v3.py` | 214 | L1 | **把 A+B+G 三套合并成一份真 prompt 的脚本**（含 `STYLE` 常量、`PROMPT_HEAD`、`validate()`） |
| `polish_batch.py` | — | L1 | 批量润色：A 派 / G 派两套 prompt 分别跑，目标比例 4:6 |
| `polish_export.py` | — | L1 | 导出批量结果 |
| `polish_audit.py` | — | L1 | 用「最终版规则」重判历史样本（防止早期规则漏放） |
| `polish_sample.py` | — | L1 | 抽样 + 单条试跑 |

> 未收进本目录的大文件：`polish-batch.jsonl`（8.4 MB）、`polish-batch-20261105-2036-run2.json`（8.1 MB）及各次 `.log`。
> 它们的统计结论与适用范围见 `../../experiments/03-offline-polish-batch-20261105.md`。

## 一条容易被误读的事实

`brief-C-grok.md` 是**项目方**写的任务书，不是用户说的。
「玩梗 / 幽默 / 口吻」这个方向由它提出，Grok 的贡献是把它写成可判定的 G1–G16。
全库检索找不到用户说过「玩梗、口吻、幽默、梗密度、冷梗、地狱笑话、双关」这些词。
详见 `../../README.md §3.3` 与 `../../MISSING_MATERIALS.md §2.2`。
