# 1 词真实金丝雀压测报告（2026-11-20）

## 汇总

- 词数：1（高踩钉子户 10 / 常规对照 10）
- 通过率：**1 / 1（100.0%）**；高踩钉子户 1/10，常规对照 0/10
- 失败词：无
- 总耗时：**2 分 7 秒**（逐词耗时合计 127.2s，平均 127.2s/词；整轮墙钟 2 分 7 秒）
- 硬拦截总次数：**0 次**（0 个词命中，命中条目共 0 条）
- 总 attempts：1（hard.check 1 次 / judge 1 次）
- 排雷各项 true 次数（每词最后一次 judge，共 1/1 词有 judge 结果）：has_unknown_part 0 · is_robot_tone 0 · is_logic_broken 0 · phonetic_drift_unwarned 0 · violates_user_intent 0
- 排雷缺键（null）次数：violates_user_intent 1
- 说明：1 个词最后一次 judge 未输出 violates_user_intent（无用户原话时该题不适用，judge() 解析为 null；当前 `_passed` 已兼容 None，其余四项仍须严格 false）。
- 生成模型：ds-v41-flash（GLM 5.3）
- Judge：providers.judge_target 自动选择 → wb/cn:glm-5.3
- 日期：2026-11-20
- 环境：REWRITE_DATA_DIR=tests\_canary_data_20261120；严格串行，无并发；单次上限 600 秒
- 脚本：`python tests/canary_20.py`（tests\canary_20.py）

| # | 词 | 类别 | 结果 | 新版方法 | attempts | 硬拦截 | 耗时 |
|---|---|---|---|---|---|---|---|
| 1 | obscure | 高踩钉子户 | ✅ 通过 | sound（谐音） | 1 | 0 | 127.2s |

## 逐词明细

### 1. obscure（高踩钉子户）

- 结果：✅ 通过
- 新版方法：sound（谐音）
- 耗时：generate_1 75.7s · polish_1 24.0s · judge_1 27.3s · total 127.0s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① obscure 拆成 ob + scure，真读音 /əbˈskjʊr/ 跟字母拆法对不上，读快了像「饿不·死去」两截，声音压在嗓子眼里。
>
> ② 把「饿不·死去」想成一个人饿得没力气、嗓子嘶哑，话像快断气一样含混，旁人竖起耳朵也听不明白。
>
> ③ 声音闷住、内容糊住，眼睛看不清，耳朵也听不懂 ——> 晦涩的；使模糊。

**旧版**（`data/results/obscure.json` 最后一条，method=meaning（含义联想），updatedAt=2026-11-19T17:26:09+08:00）

> ① 先别拆字母，把 obscure 想成一团横在眼前的暗影：它不把东西拿走，只是挡在光和眼睛之间，让你看不真切。
>
> ② 这团暗影先让光变弱，但还没到全黑，眼前只剩一片昏暗；等它压到字和道理上，细节糊成一片，你怎么也读不进去，读起来就是「晦涩难懂的」。
>
> ③ 它遮住一个人的名字和踪迹，外人查来查去也对不上号，那这个人就没人知道，也就是「不为人知」；要是遮住一个地方，那地方就成了「偏僻的」角落。
>
> ④ 反过来，你亲手把这团暗影盖上去，把东西弄糊、藏起、压暗，这一串动作就是「使模糊、使隐没、使变暗」 ——> 不为人知的；晦涩难懂的；昏暗的；偏僻的；使模糊；使隐没；使变暗。

---

## 附：修复前门禁 smoke（cathedral，真实调用）

> 本节是 v8-backend 修复 `_passed` 空 instruction 兼容前的历史证据，不计入上方 20 词通过率；数字全部来自 `canary_20_results_20261120_smoke.json`。

- 运行时间：2026-11-20T00:53:49+08:00 → 2026-11-20T00:56:19+08:00
- 模型：glm53；Judge：wb/cn:deepseek-v4.1-flash
- 总耗时：150.5s；attempts：2；结果：unverified
- 新版方法：shape（字形联想）

- 第1轮：generate 21.9s / polish 12.1s / judge 27.4s；排雷 has_unknown_part=false · is_robot_tone=false · is_logic_broken=true · phonetic_drift_unwarned=true · violates_user_intent=null；scores 拆分成立 10 · 方法选对 10 · 谐音过关 0 · 逻辑连贯 0 · 文本充足 0 · 像人说话 10
- 第2轮：generate 53.6s / polish 10.6s / judge 24.8s；排雷 has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null；scores 拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10
  - 本轮前四项排雷全 false、scores 无 0 分，但 `violates_user_intent` 缺失（null）→ `_passed` 判不过。
- 评委 intent（日志）：第1轮 other / 第2轮 other

---

本报告由 `tests/canary_20.py` 根据本次真实运行输出自动生成；所有数字来自脚本结果 JSON，无手写。
