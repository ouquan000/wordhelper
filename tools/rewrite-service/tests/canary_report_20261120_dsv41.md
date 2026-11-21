# 8 词真实金丝雀压测报告（2026-11-20）

## 汇总

- 词数：8（高踩钉子户 10 / 常规对照 10）
- 通过率：**8 / 8（100.0%）**；高踩钉子户 4/10，常规对照 4/10
- 失败词：无
- 总耗时：**12 分 52 秒**（逐词耗时合计 772.1s，平均 96.5s/词；整轮墙钟 12 分 52 秒）
- 硬拦截总次数：**0 次**（0 个词命中，命中条目共 0 条）
- 总 attempts：8（hard.check 8 次 / judge 8 次）
- 排雷各项 true 次数（每词最后一次 judge，共 8/8 词有 judge 结果）：has_unknown_part 0 · is_robot_tone 0 · is_logic_broken 0 · phonetic_drift_unwarned 0 · violates_user_intent 0
- 排雷缺键（null）次数：violates_user_intent 3
- 说明：3 个词最后一次 judge 未输出 violates_user_intent（无用户原话时该题不适用，judge() 解析为 null；当前 `_passed` 已兼容 None，其余四项仍须严格 false）。
- 生成模型：ds-v41-flash（GLM 5.3）
- Judge：providers.judge_target 自动选择 → wb/cn:glm-5.3
- 日期：2026-11-20
- 环境：REWRITE_DATA_DIR=tests\_canary_data_20261120；严格串行，无并发；单次上限 600 秒
- 脚本：`python tests/canary_20.py`（tests\canary_20.py）

| # | 词 | 类别 | 结果 | 新版方法 | attempts | 硬拦截 | 耗时 |
|---|---|---|---|---|---|---|---|
| 1 | cathedral | 高踩钉子户 | ✅ 通过 | shape（字形联想） | 1 | 0 | 88.3s |
| 2 | monopoly | 高踩钉子户 | ✅ 通过 | sound（谐音） | 1 | 0 | 90.7s |
| 3 | adolescent | 高踩钉子户 | ✅ 通过 | sound（谐音） | 1 | 0 | 126.8s |
| 4 | casual | 高踩钉子户 | ✅ 通过 | sound（谐音） | 1 | 0 | 92.7s |
| 5 | allege | 常规对照 | ✅ 通过 | shape（字形联想） | 1 | 0 | 105.2s |
| 6 | beforehand | 常规对照 | ✅ 通过 | root（词根词缀） | 1 | 0 | 40.4s |
| 7 | message | 常规对照 | ✅ 通过 | sound（谐音） | 1 | 0 | 116.3s |
| 8 | behavior | 常规对照 | ✅ 通过 | shape（字形联想） | 1 | 0 | 111.7s |

## 逐词明细

### 1. cathedral（高踩钉子户）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 69.9s · polish_1 11.1s · judge_1 7.1s · total 88.1s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① cat 就是“猫”，紧挨着的 he 就是“他”，前五个字母先把猫和他排在一起，词尾 dral 挂在后面，拼写记成 cat-he-dral。
>
> ② 猫和他并排站在门口，整栋建筑把他们衬成两个小点，能把人衬得这么小的只能是高得离谱的教堂。
>
> ③ 真读音是 /kəˈθiːdrəl/，cat 和 he 不能分开念，词尾 dral 的 /drəl/ 在石柱间拖成长长的回声。
>
> ④ 猫和他被大穹顶压成小点，dral 的尾音又在空殿里拖长，两件事一合就是那座巨型教堂 ——> 大教堂；主教座堂。

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

### 2. monopoly（高踩钉子户）

- 结果：✅ 通过
- 新版方法：sound（谐音）
- 耗时：generate_1 68.2s · polish_1 14.5s · judge_1 7.7s · total 90.5s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① monopoly 真读 /məˈnɒpəli/，我按声音把它切成 mo-no-po-ly，耳朵听着就是「莫 No 破例」。
>
> ② 掌柜站在门口把「莫 No 破例」当规矩，谁求情想分一杯羹都摆手挡回去。
>
> ③ 货源、客源、价格全被这个 No 堵住，只剩他一家能进货卖货。
>
> ④ 一条街的买卖都由这一家说了算，别人插不进来 ——> 垄断；专营。

**旧版**（`data/results/monopoly.json` 最后一条，method=sound（谐音），updatedAt=2026-11-19T18:11:05+08:00）

> ① monopoly 读 /məˈnɒpəli/，顺着读音切成 mə、nɒ、pə、li 四段，每段配一个汉字。
>
> ② 四段逐一对过去：mə 是「么」，nɒ 是「诺」，pə 是「婆」，li 是「利」，连起来就是「么诺婆利」。
>
> ③ 把这四个字摊成一幅画：整条街只有这位婆子点头（诺）才算数，别家想开张，她不点头就开不成。
>
> ④ 进货、定价、利润（利）全攥在她一个人手里，外人挤不进来，这条街的生意只归她一家 ——> 垄断；独占；专营；专卖权；独占事业。

### 3. adolescent（高踩钉子户）

- 结果：✅ 通过
- 新版方法：sound（谐音）
- 耗时：generate_1 88.0s · polish_1 30.0s · judge_1 8.6s · total 126.6s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① adolescent 一口气快读，像「挨得乐死」：刚挨到一点乐子，就乐得收不住。
>
> ② 谁都从这个时候过来：一点小事就激动、冲动、上头，拦都拦不住，浑身一股没长牢的劲儿。
>
> ③ 这样又躁又没长大的人，正是青春期的那批人 ——> 青春期的；青少年。

**旧版**（`data/results/adolescent.json` 最后一条，method=shape（字形联想），updatedAt=2026-11-19T20:09:44+08:00）

> ① 先按字母切：adolescent 切成 a + dole + scent，三段都是现成英文词，拼写对得上。
>
> ② a 就是冠词“一个”；dole 是救济金、接济款，放在学生身上，就是还得跟家里伸手要的生活费；scent 是身上的气味。
>
> ③ 把三段叠到同一个人身上：一个还靠家里接济、身上开始有汗味的半大孩子，卡在儿童和成人中间。
>
> ④ 这种正在长个儿、变声，卡在儿童和成人之间的人就是青少年；放到阶段上说，就是青春期的、青少年的 ——> 青春期的；青少年的；青少年。

### 4. casual（高踩钉子户）

- 结果：✅ 通过
- 新版方法：sound（谐音）
- 耗时：generate_1 49.1s · polish_1 33.3s · judge_1 10.1s · total 92.5s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① casual 真读音是 /ˈkæʒuəl/，顺音滑出来像“卡住哦”，s 读 /ʒ/，别念成“苏”。
>
> ② “卡住哦”一出口，就套在一身板正西装上：一抬手，袖口卡住；一低头，领口也卡住；人只好扯松衣服。
>
> ③ 衣服一松，人也不端着了，领口敞开、袖子撸起，怎么舒服怎么来。
>
> ④ 衣服不再勒手腕、卡领口，人也不摆正式架子 ——> 随意的；非正式的；休闲的。

**旧版**：无旧版记录

### 5. allege（常规对照）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 79.7s · polish_1 16.0s · judge_1 9.2s · total 105.0s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① allege 开头是 all，指所有人；后面 ege 像 edge（边沿）少写一个 d，我把这串字母记成所有人把一个人堵到边沿。
>
> ② 真读音是 /əˈledʒ/，开头的 all 不读“奥尔”，堵他的那伙人里突然有人伸手指着他喊“就是他干的”。
>
> ③ 这一喊只是当众把罪名说死，喊话的人手里没有证据，被指的人还没被证明有罪。
>
> ④ 这种当众指认、把没证实的结论咬定 ——> 断言；指控（未经证实）。

**旧版**：无旧版记录

### 6. beforehand（常规对照）

- 结果：✅ 通过
- 新版方法：root（词根词缀）
- 耗时：generate_1 10.6s · polish_1 24.5s · judge_1 5.1s · total 40.2s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① before 加 hand 就是全部词形：before 是你早认识的“在……之前”，读 /bɪˈfɔː/；hand 是“手”，读 /hænd/；合起来读 /bɪˈfɔːhænd/，重音落在 fore 上，不用再拆。
>
> ② 干活得靠手，hand 就是动手；before 加在前面，把时间卡在“动手之前”，先让你把事情办掉。
>
> ③ 事情还没开始，手还没伸出去，准备已经做好 ——> 提前；事先。

**旧版**：无旧版记录

### 7. message（常规对照）

- 结果：✅ 通过
- 新版方法：sound（谐音）
- 耗时：generate_1 84.7s · polish_1 21.5s · judge_1 9.9s · total 116.0s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① mes 加 sage 拼成 message，sage 在这里不读 /seɪdʒ/，整词读 /ˈmesɪdʒ/，听着像「没事事」：嘴上说没事，顺手把事儿递给对方。
>
> ② 嘴里一句「没事」，其实在把某件事传到别人耳朵里，让对面接住。
>
> ③ 传过去的那句话、那条事儿，指的就是 ——> 消息；信息；口信。

**旧版**：无旧版记录

### 8. behavior（常规对照）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 56.6s · polish_1 50.9s · judge_1 4.0s · total 111.5s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① behavior 我按字形切成 be + hav + ior：be 是熟词，hav 是 have 去掉尾 e，ior 只当词尾。
>
> ② 我把 be 记成“是”，把 hav 记成“有”：一个人“是”什么姿态，手里“有”什么动作，旁人都看得见。
>
> ③ 它真读 /bɪˈheɪvjər/，拼写里的 hav 和 ior 一到读音就粘成一块，别按 be-have-ior 硬念。
>
> ④ 把旁人看见的姿态和他做出来的动作合到一起 ——> 行为；举止；表现。

**旧版**：无旧版记录

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
