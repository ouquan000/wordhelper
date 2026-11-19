# 20 词真实金丝雀压测报告（2026-11-20）

## 汇总

- 词数：1（高踩钉子户 10 / 常规对照 10）
- 通过率：**0 / 1（0.0%）**；高踩钉子户 0/10，常规对照 0/10
- 失败词：cathedral
- 总耗时：**2 分 30 秒**（逐词耗时合计 150.5s，平均 150.5s/词；整轮墙钟 2 分 30 秒）
- 硬拦截总次数：**0 次**（0 个词命中，命中条目共 0 条）
- 排雷各项 true 次数（每词最后一次 judge，共 1/1 词有 judge 结果）：has_unknown_part 0 · is_robot_tone 0 · is_logic_broken 0 · phonetic_drift_unwarned 0 · violates_user_intent 0
- 生成模型：glm53（GLM 5.3）
- Judge：providers.judge_target 自动选择 → wb/cn:deepseek-v4.1-flash
- 日期：2026-11-20
- 环境：REWRITE_DATA_DIR=tests\_canary_data_20261120；严格串行，无并发；单次上限 600 秒
- 脚本：`python tests/canary_20.py`（tests\canary_20.py）

| # | 词 | 类别 | 结果 | 新版方法 | attempts | 硬拦截 | 耗时 |
|---|---|---|---|---|---|---|---|
| 1 | cathedral | 高踩钉子户 | ❌ 未通过 | shape（字形联想） | 2 | 0 | 150.5s |

## 逐词明细

### 1. cathedral（高踩钉子户）

- 结果：❌ 未通过（unverified）
- 新版方法：shape（字形联想）
- 耗时：generate_1 21.9s · polish_1 12.1s · judge_1 27.4s · generate_2 53.6s · polish_2 10.6s · judge_2 24.8s · total 150.3s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10
- 备注：两轮都没过评审判定，交付历史最佳（第2轮）；最后一轮问题：最低分 10（{"拆分成立": 10, "方法选对": 10, "谐音过关": 10, "逻辑连贯": 10, "文本充足": 10, "像人说话": 10}）

**新版全文**

> ① cathedral 里最扎眼的是开头的 cat 和中间的 he，切成 cat / he / dral 三块，闭上眼也能一块块拼回来。
>
> ② cat 是猫，he 是他：一只猫贴着他的脚跟，蹑手蹑脚去推一扇又高又沉的大石门。
>
> ③ 尾块 dral 真读 /drəl/，快念像「得儿——」；整词读 /kəˈθiːdrəl/，cat 里的 t 变轻、he 念得含糊，th 要咬着舌尖。
>
> ④ 石门一开，管风琴正好轰出一个低音，高得看不见顶的大厅「得儿——」嗡嗡震起来，猫吓得毛全炸开。
>
> ⑤ 猫、他、这声一进门就震得人发懵的高顶嗡鸣 ——> 大教堂。

**旧版**（`data/results/cathedral.json` 最后一条，method=shape（字形联想），updatedAt=2026-11-19T21:03:26+08:00）

> ① 先看拼写，cathedral 分成 cat | he | dral 三块，每块都当成教堂的构件来认，不靠读音也不靠词根。
>
> ② cat 这块别念成“猫”，只取 C、A、T 的形状：C 像半圆拱，A 像尖顶，T 像十字架，正立面先搭起来。
>
> ③ he 接在 cat 后面：H 像两座钟塔，E 像一排窗格，把正立面一路带进中殿。
>
> ④ dral 收尾：D 像门洞，R 像玫瑰窗，A 像尖拱，L 像扶壁，门窗和支撑就补齐了。
>
> ⑤ C、A、T 搭出正立面，H、E 接上双塔和窗格，D、R、A、L 补齐门洞、玫瑰窗、尖拱和扶壁，一座主教座堂就在拼写里立起来 ——> adj. 大教堂的；主教座堂的；n. 大教堂。

---

本报告由 `tests/canary_20.py` 根据本次真实运行输出自动生成；所有数字来自脚本结果 JSON，无手写。
