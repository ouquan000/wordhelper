# 20 词真实金丝雀压测报告（2026-11-20）

## 汇总

- 词数：20（高踩钉子户 10 / 常规对照 10）
- 通过率：**19 / 20（95.0%）**；高踩钉子户 9/10，常规对照 10/10
- 失败词：obscure
- 总耗时：**40 分 1 秒**（逐词耗时合计 2401.4s，平均 120.1s/词；整轮墙钟 40 分 2 秒）
- 硬拦截总次数：**0 次**（0 个词命中，命中条目共 0 条）
- 总 attempts：25（hard.check 22 次 / judge 22 次；obscure、conducive、message 有 attempt 未进入 judge（如生成非 JSON））
- 排雷各项 true 次数（每词最后一次 judge，共 20/20 词有 judge 结果）：has_unknown_part 0 · is_robot_tone 0 · is_logic_broken 0 · phonetic_drift_unwarned 1 · violates_user_intent 0
- 排雷缺键（null）次数：violates_user_intent 5
- 说明：5 个词最后一次 judge 未输出 violates_user_intent（无用户原话时该题不适用，judge() 解析为 null；当前 `_passed` 已兼容 None，其余四项仍须严格 false）。
- 生成模型：ds-v41-flash（GLM 5.3）
- Judge：providers.judge_target 自动选择 → wb/cn:glm-5.3
- 日期：2026-11-20
- 环境：REWRITE_DATA_DIR=tests\_canary_data_20261120；严格串行，无并发；单次上限 600 秒
- 脚本：`python tests/canary_20.py`（tests\canary_20.py）

| # | 词 | 类别 | 结果 | 新版方法 | attempts | 硬拦截 | 耗时 |
|---|---|---|---|---|---|---|---|
| 1 | cathedral | 高踩钉子户 | ✅ 通过 | shape（字形联想） | 1 | 0 | 155.7s |
| 2 | obscure | 高踩钉子户 | ❌ 未通过 | root（词根词缀） | 2 | 0 | 240.0s |
| 3 | endeavor | 高踩钉子户 | ✅ 通过 | sound（谐音） | 1 | 0 | 134.2s |
| 4 | monopoly | 高踩钉子户 | ✅ 通过 | sound（谐音） | 2 | 0 | 156.9s |
| 5 | subtle | 高踩钉子户 | ✅ 通过 | shape（字形联想） | 1 | 0 | 56.6s |
| 6 | conducive | 高踩钉子户 | ✅ 通过 | root（词根词缀） | 2 | 0 | 180.2s |
| 7 | subsidy | 高踩钉子户 | ✅ 通过 | root（词根词缀） | 1 | 0 | 145.6s |
| 8 | adolescent | 高踩钉子户 | ✅ 通过 | shape（字形联想） | 1 | 0 | 105.5s |
| 9 | substantial | 高踩钉子户 | ✅ 通过 | shape（字形联想） | 1 | 0 | 76.5s |
| 10 | casual | 高踩钉子户 | ✅ 通过 | sound（谐音） | 1 | 0 | 96.6s |
| 11 | diminish | 常规对照 | ✅ 通过 | root（词根词缀） | 1 | 0 | 81.4s |
| 12 | random | 常规对照 | ✅ 通过 | shape（字形联想） | 1 | 0 | 39.1s |
| 13 | avoid | 常规对照 | ✅ 通过 | shape（字形联想） | 1 | 0 | 125.8s |
| 14 | allege | 常规对照 | ✅ 通过 | shape（字形联想） | 2 | 0 | 230.3s |
| 15 | pledge | 常规对照 | ✅ 通过 | shape（字形联想） | 1 | 0 | 95.7s |
| 16 | beforehand | 常规对照 | ✅ 通过 | root（词根词缀） | 1 | 0 | 57.3s |
| 17 | stumble | 常规对照 | ✅ 通过 | shape（字形联想） | 1 | 0 | 100.2s |
| 18 | message | 常规对照 | ✅ 通过 | root（词根词缀） | 2 | 0 | 162.9s |
| 19 | behavior | 常规对照 | ✅ 通过 | shape（字形联想） | 1 | 0 | 77.8s |
| 20 | emotion | 常规对照 | ✅ 通过 | root（词根词缀） | 1 | 0 | 83.1s |

## 逐词明细

### 1. cathedral（高踩钉子户）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 43.3s · polish_1 17.1s · judge_1 7.0s · total 67.4s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10
- 错误：第1次执行：PipelineError: 两轮都没有可用结果：第1轮：生成结果不是 JSON：{"method":"shape","split":["cat","he","dral"],"elements":[{"part":"cat","method":"shape","reasoning":"开头三个字母就是熟词 cat，先抓住猫这个扎眼形象，给长词一个钩子。"},{"part":"he","method":"shape","reasoning":"紧接着就是熟；第2轮：WB 冷却中 30s：HTTP 503 {"error":{"type":"router_unavailable","message":"wb-router: no eligible

**新版全文**

> ① 拼写切成 cat + he + dral：前三个字母是猫，中间两个字母是他，剩下 dral 这一截留作 /drəl/ 的尾音。
>
> ② 猫蹲在教堂长椅上，他站在下面抬头看彩窗，屋顶太高，声音拖成 /drəl/ 的回响。
>
> ③ 这个词真读 /kəˈθiːdrəl/，开头不是 cat 的 /kæt/，而是轻轻的 /kə/，cat 只当眼睛认拼写的钩子。
>
> ④ 猫、他、长椅、彩窗和拖长的回声全挤进一座尖顶高耸的大房子 ——> 大教堂。

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

### 2. obscure（高踩钉子户）

- 结果：❌ 未通过（unverified）
- 新版方法：root（词根词缀）
- 耗时：generate_2 75.7s · polish_2 66.6s · judge_2 21.2s · total 239.8s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=true · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 0 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10
- 备注：两轮都没过评审判定，交付历史最佳（第2轮）；最后一轮问题：用了 ob+scure 拼写拆法但全程没提醒真实读音和音标；作废轮次：第1轮：生成结果不是 JSON：{
  "method": "sound",
  "split": ["ob

**新版全文**

> ① ob 就是挡在前面往上盖，obstacle 拦在路上的那种盖上来，这里取「盖上来」。
>
> ② scure 几乎就是 cure，cure 把毛病弄清楚、治好，人心里就亮堂。
>
> ③ 拿 ob 那股盖劲儿压住 cure 的亮堂，亮的地方被挡住，脑子里就弄不清、看不明白。
>
> ④ 看不清、听不懂、被遮住 ——> 晦涩的；使模糊；adj. 无名的。

**旧版**（`data/results/obscure.json` 最后一条，method=meaning（含义联想），updatedAt=2026-11-19T17:26:09+08:00）

> ① 先别拆字母，把 obscure 想成一团横在眼前的暗影：它不把东西拿走，只是挡在光和眼睛之间，让你看不真切。
>
> ② 这团暗影先让光变弱，但还没到全黑，眼前只剩一片昏暗；等它压到字和道理上，细节糊成一片，你怎么也读不进去，读起来就是「晦涩难懂的」。
>
> ③ 它遮住一个人的名字和踪迹，外人查来查去也对不上号，那这个人就没人知道，也就是「不为人知」；要是遮住一个地方，那地方就成了「偏僻的」角落。
>
> ④ 反过来，你亲手把这团暗影盖上去，把东西弄糊、藏起、压暗，这一串动作就是「使模糊、使隐没、使变暗」 ——> 不为人知的；晦涩难懂的；昏暗的；偏僻的；使模糊；使隐没；使变暗。

### 3. endeavor（高踩钉子户）

- 结果：✅ 通过
- 新版方法：sound（谐音）
- 耗时：generate_1 80.2s · polish_1 41.9s · judge_1 11.8s · total 133.9s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① endeavor 念 /ɪnˈdevər/，一口气念下来像「俺待我」：俺在这儿等我、替我顶着，尾音 vor 收住。
>
> ② 一个人把活儿往自己身上揽，说「俺来、俺待在这儿不走了」，咬着牙也要办成。
>
> ③ 咬牙使劲去办一件难事 ——> 努力；尽力；endeavor 作名词就是「努力、尝试」。

**旧版**（`data/results/endeavor.json` 最后一条，method=shape（字形联想），updatedAt=2026-11-19T18:43:22+08:00）

> ① 先看字形，endeavor 打头三个字母就是 end，拿它当第一个锚点，后面剩下的一整块就是 eavor。
>
> ② 再看读音，eavor 这一块念起来跟 ever 同音，直接借 ever 的「永远」来记。
>
> ③ 把两个锚点串成一条动作链：手上一直使着劲，使到 end 那个尽头才收手；ever 的「永远」让这股劲一直不停，合起来就是「把劲一直使到尽头」。
>
> ④ 这股劲就是长时间不松手的努力，落到一件具体的事上，又成了认真的尝试、一桩要长期做的事业 ——> 努力；尽力；尝试。

### 4. monopoly（高踩钉子户）

- 结果：✅ 通过
- 新版方法：sound（谐音）
- 耗时：generate_1 22.6s · polish_1 15.6s · judge_1 38.4s · generate_2 36.4s · polish_2 35.7s · judge_2 7.9s · total 156.7s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① monopoly 读 /məˈnɒpəli/，拆两半念：mono 轻读像人名「莫诺」，poly 像「破例」，连起来就是「莫诺破例」。
>
> ② 这条街的规矩对谁都一样，唯独莫诺能破例：别人摆摊都被赶走，他一句话就把整排铺面全占了。
>
> ③ 他能破例，靠的是这行买卖只剩他一家，别人连摆张桌子的缝都插不进去。
>
> ④ 一个行业只剩他一家、规矩全由他定 ——> 垄断；专营（权）。

**旧版**（`data/results/monopoly.json` 最后一条，method=sound（谐音），updatedAt=2026-11-19T18:11:05+08:00）

> ① monopoly 读 /məˈnɒpəli/，顺着读音切成 mə、nɒ、pə、li 四段，每段配一个汉字。
>
> ② 四段逐一对过去：mə 是「么」，nɒ 是「诺」，pə 是「婆」，li 是「利」，连起来就是「么诺婆利」。
>
> ③ 把这四个字摊成一幅画：整条街只有这位婆子点头（诺）才算数，别家想开张，她不点头就开不成。
>
> ④ 进货、定价、利润（利）全攥在她一个人手里，外人挤不进来，这条街的生意只归她一家 ——> 垄断；独占；专营；专卖权；独占事业。

### 5. subtle（高踩钉子户）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 29.9s · polish_1 18.7s · judge_1 7.9s · total 56.5s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① 把 subtle 切成 sub + tle：sub 是“在下面”，tle 借 little 的尾巴记“小”。
>
> ② 把“小”压到“下面”：表面扫一眼看不出差别，真正那一点小差异藏在底下。
>
> ③ 藏在底下、小到不扎眼，差别就在那儿，得凑近才看得见。
>
> ④ 真读音是 /ˈsʌtəl/，这个词里 sub 的 b 不发音，别按字母念成“萨布头” ——> 微妙的；细微的；不易察觉的。

**旧版**（`data/results/subtle.json` 最后一条，method=root（词根词缀），updatedAt=2026-11-19T19:07:42+08:00）

> ① 先把 subtle 拆成 sub 和 tle 两截：sub 是“在下面”，tle 来自拉丁语 tela“网、织物”，到英语里磨短了，老底子讲的是织得细密。
>
> ② 脑子里挂一张网：线越细，网眼越密，远看像什么都没有，得凑到眼前才看得出纹路。
>
> ③ 这张网藏在“下面”，两边一合，就是“细密而不露”；这股劲儿碰到差别、变化，就是细微、微妙、不易察觉。
>
> ④ 同一股细劲儿用在眼睛和耳朵上，是能捏住细线的敏锐；用在藏起来的心思上，是狡猾；用在空气里，是细而少的稀薄。
>
> ⑤ 所以别把它当成“量少”，它抓的是“细密而不露” ——> 微妙的；细微的；不易察觉的；敏锐的；狡猾的；稀薄的。

### 6. conducive（高踩钉子户）

- 结果：✅ 通过
- 新版方法：root（词根词缀）
- 耗时：generate_2 108.2s · polish_2 62.6s · judge_2 9.2s · total 180.1s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① con 是「一起」，duc 是「引、带路」，合起来就是把人一起往一个方向引。
>
> ② 加上 ive 变成「……的」，形容某样东西有本事把大家伙一起引到某个结果上去。
>
> ③ 好东西把人一起引向健康、引向成功，就是「有助于……的」。
>
> ④ 所以 conducive 就是「能引导到好结果上去的」 ——> 有助于……的；促成的。

**旧版**（`data/results/conducive.json` 最后一条，method=root（词根词缀），updatedAt=2026-11-19T18:38:00+08:00）

> ① 先分清两个长得像的词：conducive 和 conductive 只差一个 t，那个 t 属于 conduct 的「传导」；导电导热跟这里没关系，先划掉。
>
> ② 划掉 t 以后，剩下的 conducive 拆成 con- + duc + -ive：con- 是「一起」，duc 是「引导、带」，-ive 是形容词的尾巴。
>
> ③ 这里的「引导」是把条件往结果上引：几股条件一起汇到同一个地方，那个结果就更容易出现，也就是共同促成。
>
> ④ 结尾的 -ive 把这股「一起往结果上带」的劲收成一个形容词，安到条件、环境头上 ——> 有益的；有助于的；有益于的；助长的。

### 7. subsidy（高踩钉子户）

- 结果：✅ 通过
- 新版方法：root（词根词缀）
- 耗时：generate_1 81.1s · polish_1 58.7s · judge_1 5.6s · total 145.4s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① subsidy 拆成 sub、sid、y，sub 是在下面，就像 sub-way 是地下通道，所以 sub 先记成「底下垫着」，整词读 /ˈsʌbsədi/，sid 别念成 side。
>
> ② sid 跟 sit 同源，都是「坐」，有人坐在你下面托着你、撑着你。
>
> ③ 底下有人坐着托你一把，就是给你垫钱、帮你把事撑住，这笔垫着的钱就叫补贴。
>
> ④ 所以 subsidy 就是政府或机构在下面垫着、帮你撑住的那笔钱 ——> 补贴；津贴。

**旧版**（`data/results/subsidy.json` 最后一条，method=root（词根词缀），updatedAt=2026-11-19T20:56:45+08:00）

> ① 先把 subsidy 拆成 sub + sid + y：sub- 是「在下、垫底」，sid 是「坐」，末尾的 -y 把它收成一个名词，指一样东西。
>
> ② 合起来，画面是一支「坐在下面」的后备部队：它不站前排，坐在阵后待命，等前方快顶不住时再起身托一把。
>
> ③ 这笔钱也一样，平时蹲在后头不出面，等某个行业或群体快撑不住，才从底下垫上去把局面稳住。
>
> ④ 所以 subsidy 就是国家或机构留在后面、从底下垫上去托住某一方的那笔钱 ——> 补贴、津贴、补助金。

### 8. adolescent（高踩钉子户）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 78.4s · polish_1 16.6s · judge_1 10.2s · total 105.3s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① adolescent 拆成 a + dole + scent：a 是轻轻一个「啊」，dole 按 /doʊl/ 读像「兜」，scent 是熟词「气味」。
>
> ② 一个「啊」刚冒头的人，兜里揣着奶香，身上的气味没换成大人的。
>
> ③ 拼写记 a-dole-scent，真读音是 /ˌædəˈlesnt/，中间的 dole 别照 /doʊl/ 硬念，听着像「勒」 ——> 青少年；青春期的。

**旧版**（`data/results/adolescent.json` 最后一条，method=shape（字形联想），updatedAt=2026-11-19T20:09:44+08:00）

> ① 先按字母切：adolescent 切成 a + dole + scent，三段都是现成英文词，拼写对得上。
>
> ② a 就是冠词“一个”；dole 是救济金、接济款，放在学生身上，就是还得跟家里伸手要的生活费；scent 是身上的气味。
>
> ③ 把三段叠到同一个人身上：一个还靠家里接济、身上开始有汗味的半大孩子，卡在儿童和成人中间。
>
> ④ 这种正在长个儿、变声，卡在儿童和成人之间的人就是青少年；放到阶段上说，就是青春期的、青少年的 ——> 青春期的；青少年的；青少年。

### 9. substantial（高踩钉子户）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 56.8s · polish_1 14.7s · judge_1 4.8s · total 76.4s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① substantial 按字形切成 sub-stan-tial：sub 是在下面，stan 像 stand 的“站”，tial 收成词尾。
>
> ② 三块连起来，就是有东西在下面站住、撑住，上面的重量压下来也不晃。
>
> ③ 真读音是 /səbˈstænʃəl/，tial 念 /ʃəl/，别按字母硬拼，心里只留“下面站得住”。
>
> ④ 下面站得住、撑得起分量，就是有实体、有底气 ——> 大量的；重大的；结实的；实质的。

**旧版**（`data/results/substantial.json` 最后一条，method=root（词根词缀），updatedAt=2026-11-09T00:17:59+08:00）

> ① substantial 就按 sub-、stant、-ial 三块看，sub- 是在下面，stant 是站，-ial 是形容词的尾巴。
>
> ② 你想想家里的饭桌：sub- 让桌腿待在桌面底下，stant 让它稳稳站住。
>
> ③ 桌腿能在底下站住、把桌面撑起来，就不是空心摆设，得有实打实的木料和分量。
>
> ④ sub（在下）+ stant（站）+ -ial（形容词）三块一合，就是“在底下站住、把实打实的东西撑起来”的，数量、分量和影响都小不了 ——> 大量的；实质性的；重大的。

### 10. casual（高踩钉子户）

- 结果：✅ 通过
- 新版方法：sound（谐音）
- 耗时：generate_1 73.4s · polish_1 15.3s · judge_1 7.7s · total 96.4s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① casual 读 /ˈkæʒuəl/，前头 kæ 像「咖」，后面 ʒuəl 连起来像「坐儿」，我脑子里立马冒出咖啡店里随便往椅子上一坐。
>
> ② 不用穿正装、不用约时间，套上便服推门就坐，坐下也不摆正经架势，随随便便，压根没把这场合当正经事。
>
> ③ 我把穿着随便、态度随便、碰巧遇上这几样摆到一起 ——> 随意的；随便的；非正式的。

**旧版**：无旧版记录

### 11. diminish（常规对照）

- 结果：✅ 通过
- 新版方法：root（词根词缀）
- 耗时：generate_1 40.5s · polish_1 28.6s · judge_1 12.1s · total 81.2s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① mini 你闭眼认识，就是“迷你、小号”，把末尾的 i 摘掉，剩下的 min 就是“小”。
>
> ② diminish 中间正好嵌着 min 这三个字母，眼睛扫到这一段，我马上把 min 认成“小”。
>
> ③ 把一样东西改小，就是尺寸往小缩、数量变少、分量变轻。
>
> ④ 连它有多要紧也一路往下缩 ——> 减少；缩小；降低。

**旧版**（`data/results/diminish.json` 最后一条，method=root（词根词缀），updatedAt=2026-11-09T11:17:32+08:00）

> ① 先看中间的 min，minimum、minor、minus 都带着“小、少”的意思，diminish 的底子就在这儿。
>
> ② 开头的 di- 是 de- 的弱化版，带着往下的劲儿，像把刻度往下拨一格，跟 min 合起来就是往小里走。
>
> ③ 结尾的 -ish 把“向下 + 小”收成一个动作，不是单纯说某个东西小，而是让它朝更小、更少、更低的方向变。
>
> ④ 这股劲落到体积上是变小、减少，落到力气上是削弱，落到人的分量上就是往下压，也就是贬低。 ——> v. 削弱；使变小；贬低；减少；变小

### 12. random（常规对照）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 25.5s · polish_1 7.1s · judge_1 6.3s · total 38.9s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① random 开头正好是 ran，也就是 run 的过去式：人已经跑出去了。
>
> ② 末尾 dom 取 kingdom 的尾巴记成“地盘”；random 真读 /ˈrændəm/，dom 只轻轻收成 dəm。
>
> ③ ran + dom 合起来，就是一个人在自己的地盘里没路线地乱跑，跑到哪儿算哪儿 ——> 随机的；随意的。

**旧版**（`data/results/random.json` 最后一条，method=shape（字形联想），updatedAt=2026-11-09T11:23:18+08:00）

> ① random 的拼写能干净地切成 ran 和 dom：ran 是 run 的过去式，先带上“跑”的动作；dom 借常见后缀 -dom 的样子，表示一片范围、地盘。
>
> ② 想象一块没有编号、没有路标的地界，一个人在上面乱跑，每一步都换方向，路线根本没有定数。
>
> ③ 路线不固定，下一步落在哪儿没法预判，这种乱跑没有预定顺序 ——> 随机；任意；胡乱地。

### 13. avoid（常规对照）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 83.7s · polish_1 29.5s · judge_1 12.5s · total 125.6s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① avoid 拆成 a + void，void 读 /vɔɪd/，是一块空地方，里面什么都没有；整词读 /əˈvɔɪd/，别按「a-void」硬念。
>
> ② 在 void 前面添上 a，a 就是一个，我把它念成「一条」，合起来记成「一条空当」。
>
> ③ 眼前横着一条空当，踩下去会落空，脚一迈就绕开、不往里踩。
>
> ④ 人侧身让过那条空当，就是 avoid ——> 避开；避免。

**旧版**：无旧版记录

### 14. allege（常规对照）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 80.4s · polish_1 29.8s · judge_1 22.8s · generate_2 76.0s · polish_2 15.9s · judge_2 5.2s · total 230.1s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① allege 开头 alle 眼看就是 all 多一个 e，我把前半块认成 all 那一整块“全部”。
>
> ② 真读音是 /əˈledʒ/，听着像“呃-来吉”，末尾 ge 落在“吉”上，两个 l 只出一个音，别照字母念成“阿勒格”。
>
> ③ 我把“全部”的东西公开摆到台面上讲，把一整件事说成是真的。
>
> ④ 台上把一整件事说成真的，至于有没有证据另说 ——> 声称；指认。

**旧版**：无旧版记录

### 15. pledge（常规对照）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 65.2s · polish_1 24.7s · judge_1 5.5s · total 95.5s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① ple 是 please（请）的前三个字母，这声“请”就是抓手。
>
> ② dge 是 judge（法官）的最后三个字母，和 ple 一合，人就站到法官面前。
>
> ③ 在法官面前说“请”不是求饶，而是把话押上，先立誓，再交出保证物，真读音是 /pledʒ/，开头别按 please 的 /pliːz/ 念，整个词一口气读完。
>
> ④ 当着法官把话和保证物一起押上 ——> 誓言；保证；抵押。

**旧版**：无旧版记录

### 16. beforehand（常规对照）

- 结果：✅ 通过
- 新版方法：root（词根词缀）
- 耗时：generate_1 18.7s · polish_1 30.9s · judge_1 7.4s · total 57.1s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① before 是“在……之前”，hand 是“手”，beforehand 一眼拆成 before + hand，真读 /bɪˈfɔːrhænd/，重音落在 before 的 fore。
>
> ② hand 想成事情到手的那一刻：事情到手，你才轮得到接住、处理。
>
> ③ 事情还没到手前，你就想好、备好，人已经站到事情前头。
>
> ④ 所以 beforehand 指事情发生前已经备好 ——> 事先；提前。

**旧版**：无旧版记录

### 17. stumble（常规对照）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 77.6s · polish_1 8.3s · judge_1 14.2s · total 100.0s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① stumble 拆成 st + umble，st 是 street 的开头，umble 一读就是「俺不」。
>
> ② 大街上走着一个人，嘴里直喊「俺不」，可脚偏偏不听话。
>
> ③ 他一脚踩空，身子往前一栽，差点撞到路边的台阶。
>
> ④ 记住拼写 st-umble，真读音是 /ˈstʌmbl/，umble 别按字母念成「乌姆布勒」 ——> 踉跄；绊倒；结结巴巴地说。

**旧版**：无旧版记录

### 18. message（常规对照）

- 结果：✅ 通过
- 新版方法：root（词根词缀）
- 耗时：generate_2 79.8s · polish_2 36.9s · judge_2 5.1s · total 162.7s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① 我记 message 就抓 mess 这团乱：桌上摊开的纸、杂七杂八堆成一堆，整个词读 /ˈmesɪdʒ/，age 那截收成 /ɪdʒ/，不念 /eɪdʒ/。
>
> ② 后面的 age 一接上来，就把这堆乱东西收拢、打包，捆成一整份能送出去、也能摔过来的东西。
>
> ③ 这包乱码递到你面前，你打开一看，里面是对方想说的内容。
>
> ④ 递到你手里的不是那包东西，是要你接住的信息 ——> 消息；信息。

**旧版**：无旧版记录

### 19. behavior（常规对照）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 63.8s · polish_1 9.2s · judge_1 4.5s · total 77.5s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① behavior 能清楚切成 be + hav + ior 三块，中间的 hav 就是 have 去掉末尾 e 的样子。
>
> ② 把 be 记成“是”，把 hav 记成 have 的“有”：一个人“是”什么姿态、身上“有”什么动作，合起来就是旁人看到他怎么做。
>
> ③ 别照熟词 have 硬念，behavior 的真读音是 /bɪˈheɪvjər/，中间的 hav 读 /heɪv/，不是 /hæv/。
>
> ④ 最后的 ior 只负责收尾，把前面“是什么样、有什么动作”收成一个名词 ——> 行为；举止。

**旧版**：无旧版记录

### 20. emotion（常规对照）

- 结果：✅ 通过
- 新版方法：root（词根词缀）
- 耗时：generate_1 39.0s · polish_1 36.9s · judge_1 7.0s · total 82.9s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① motion 我熟，就是“运动”，东西一动，这一段动就叫 motion；emotion 念 /ɪˈmoʊʃən/，开头的 e 一带而过。
>
> ② 前面的 e 借 exit 记，exit 是出口、人往外走，e 就取“向外”。
>
> ③ 心里先起一阵动，再把那股动往外放，放出来的就是高兴、生气、害怕这些反应。
>
> ④ 从心里往外动出来的那股劲 ——> 情绪；情感。

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
