# 实验 3：2026-11-05 全量离线润色（5250 词）

**状态**：已执行的一次性全量批处理实验。**产物未入库**（8 MB 级），只登记与统计。
**原始文件**：`D:\deepseek harness\workcodex\.tmp\` 下的 `polish-batch.jsonl`（8,376,350 B）、`polish-batch-20261105-2036-run2.json`（8,131,726 B）、各 `polish-*.log`。
**执行脚本**：`originals/style-standards/polish_batch.py`（8235 B / 2026-11-05 16:51）。

---

## 1. 它是什么

这是本项目里**唯一一次把风格标准跑在全量词表上**的实验，发生在 App 的重写服务（1.4.3）之前两天。

脚本的做法（`polish_batch.py`）：
- L21 把标准分成两派：「**G 派（grok）偏口吻与梗**，A 派（Claude）偏表达」；
- L22–31 两派用**不同的 prompt**；
- L35 目标配比 **4:6**（即四成走 G 派、六成走 A 派）；
- L97 合并 spec = `claude-A7+B7 + grok-G16`；
- 从 MindForge 的 `word-runs\<word>-<ts>\work\card.text.json` 取原文，改写后回写。

## 2. 实测结果（本次统计）

### `polish-batch.jsonl`（第一轮，逐行追加日志）

| 项 | 值 |
|---|---|
| 总行数 | 6297 |
| 涉及不同单词 | **5250** |
| `status = ok` | **5227** |
| `status = skip` | 953 |
| `status = call_error` | 60 |
| `status = invalid` | 56 |
| `status = no_run` | 1 |
| 字段 | `word / status / run / secs / school / before / after / attempt / err / errs` |

### `polish-batch-20261105-2036-run2.json`（第二轮，结果快照）

| 项 | 值 |
|---|---|
| 条目数 | 5249 |
| `ok` | 4620 |
| `skip` | 587 |
| `invalid` | 31 |
| `call_error` | 11 |

### 观察

1. **规模确认**：两轮都覆盖约 5250 个词，`run` 字段是 MindForge 的具体 `word-runs` 目录名（如 `abandon-20261021-123416`），说明它是按词逐条改写的。
2. **失败率低但非零**：第一轮 `call_error 60` + `invalid 56` ≈ 2.2%；第二轮 `invalid 31 + call_error 11` ≈ 0.8%。
3. **最重要的一个缺口**：**这批日志里没有任何评分类字段**（没有 `score` / `grade` / `verdict`）。也就是说 —— **跑完了 5250 个词，却没有留下任何质量度量**，事后无法判断这批润色到底是变好了还是变坏了。
4. 这也解释了为什么同一天 18:01 又写了 `polish_audit.py`（「用最终版规则重判…防早期规则漏放」）。但审计只跑了 **20 个词**（`polish-audit-sample.md`，31741 B），**不是全量**。

## 3. 与「口吻标准到底有没有落地」的关系

这是本次审计里最关键的一条负面证据链：

| 证据 | 事实 |
|---|---|
| `brief-C-grok.md:10`（12:24） | 要求「要像人写的、要有趣、要会玩梗」 |
| `reply-C-grok.md`（12:30） | 落成 G1–G16 + 八口吻 + 10 条自检 |
| `polish_batch.py`（16:51） | 真的按 G 派 prompt 跑了全量 |
| `polish-v3-sample.md`（14:05，5 词） | **通读无任何梗 / 双关 / 荤段子** |
| `polish-audit-sample.md`（20:43，20 词） | **通读风格克制，未见玩梗** |

**结论只能是事实层面的**：标准写得很细（16 条可判定规则），也确实被喂进了模型，但**留下的样张里看不到它生效的痕迹**。至于「是模型没执行、还是 agent 执行了但被后续清理掉了、还是样张挑选时刻意选了保守的」，**现有材料无法判定** —— 没有任何一份文档承认「玩梗标准被废弃」，也没有任何一份文档解释「为什么样张里没有梗」。

## 4. 这批产物后来去哪了

**本次审计无法确认。** 检索不到任何代码读取 `polish-batch*` 产物；App 端卡背显示的记忆思路来自 rewrite-service 的 `data/results/*.json`，不是这批文件。MindForge 的 `word-runs` 目录时间戳集中在 2026-10-20 ~ 10-21，早于这次批处理。

**所以结论按最保守的写法记录**：这是一次**独立于 App 运行链的一次性实验**，成果的去向无记录。

## 5. 适用范围

- 只对 2026-11-05 当天的 prompt（`claude-A7+B7 + grok-G16` 合并版）成立；
- 只对 MindForge 那批「整卡 22 字段」的卡片成立，**不适用于 App 现在只产出 3–5 步推导的形态**；
- 两轮跑的不是同一批 prompt（run2 在 20:36，`polish_v3.py` 在 20:42 才写完，所以 run2 很可能仍是旧 prompt 组合）—— **这一点无法从产物反推确认**，因为日志里没存 prompt 版本。
