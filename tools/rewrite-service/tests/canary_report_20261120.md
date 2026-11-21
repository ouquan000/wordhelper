# 20 词真实金丝雀压测报告（2026-11-20）

## 汇总

- 词数：20（高踩钉子户 10 / 常规对照 10）
- 通过率：**12 / 20（60.0%）**；高踩钉子户 6/10，常规对照 6/10
- 失败词：cathedral、monopoly、adolescent、casual、allege、beforehand、message、behavior
- 总耗时：**32 分 50 秒**（逐词耗时合计 1969.7s，平均 98.5s/词；整轮墙钟 32 分 50 秒）
- 硬拦截总次数：**0 次**（0 个词命中，命中条目共 0 条）
- 总 attempts：34（hard.check 33 次 / judge 33 次；cathedral 有 attempt 未进入 judge（如生成非 JSON））
- 排雷各项 true 次数（每词最后一次 judge，共 20/20 词有 judge 结果）：has_unknown_part 1 · is_robot_tone 0 · is_logic_broken 2 · phonetic_drift_unwarned 6 · violates_user_intent 0
- 排雷缺键（null）次数：violates_user_intent 16
- 说明：16 个词最后一次 judge 未输出 violates_user_intent（无用户原话时该题不适用，judge() 解析为 null；当前 `_passed` 已兼容 None，其余四项仍须严格 false）。
- 生成模型：glm53（GLM 5.3）
- Judge：providers.judge_target 自动选择 → wb/cn:deepseek-v4.1-flash
- 日期：2026-11-20
- 环境：REWRITE_DATA_DIR=tests\_canary_data_20261120；严格串行，无并发；单次上限 600 秒
- 脚本：`python tests/canary_20.py`（tests\canary_20.py）

| # | 词 | 类别 | 结果 | 新版方法 | attempts | 硬拦截 | 耗时 |
|---|---|---|---|---|---|---|---|
| 1 | cathedral | 高踩钉子户 | ❌ 未通过 | shape（字形联想） | 2 | 0 | 132.3s |
| 2 | obscure | 高踩钉子户 | ✅ 通过 | sound（谐音） | 2 | 0 | 83.9s |
| 3 | endeavor | 高踩钉子户 | ✅ 通过 | sound（谐音） | 2 | 0 | 74.1s |
| 4 | monopoly | 高踩钉子户 | ❌ 未通过 | sound（谐音） | 2 | 0 | 99.4s |
| 5 | subtle | 高踩钉子户 | ✅ 通过 | root（词根词缀） | 1 | 0 | 26.7s |
| 6 | conducive | 高踩钉子户 | ✅ 通过 | sound（谐音） | 1 | 0 | 37.2s |
| 7 | subsidy | 高踩钉子户 | ✅ 通过 | root（词根词缀） | 1 | 0 | 71.8s |
| 8 | adolescent | 高踩钉子户 | ❌ 未通过 | sound（谐音） | 2 | 0 | 146.2s |
| 9 | substantial | 高踩钉子户 | ✅ 通过 | root（词根词缀） | 2 | 0 | 84.0s |
| 10 | casual | 高踩钉子户 | ❌ 未通过 | root（词根词缀） | 2 | 0 | 69.5s |
| 11 | diminish | 常规对照 | ✅ 通过 | root（词根词缀） | 2 | 0 | 124.0s |
| 12 | random | 常规对照 | ✅ 通过 | shape（字形联想） | 2 | 0 | 82.1s |
| 13 | avoid | 常规对照 | ✅ 通过 | sound（谐音） | 1 | 0 | 51.7s |
| 14 | allege | 常规对照 | ❌ 未通过 | sound（谐音） | 2 | 0 | 317.3s |
| 15 | pledge | 常规对照 | ✅ 通过 | shape（字形联想） | 2 | 0 | 153.5s |
| 16 | beforehand | 常规对照 | ❌ 未通过 | root（词根词缀） | 2 | 0 | 54.8s |
| 17 | stumble | 常规对照 | ✅ 通过 | shape（字形联想） | 1 | 0 | 135.9s |
| 18 | message | 常规对照 | ❌ 未通过 | contrast（对比辨析） | 2 | 0 | 83.1s |
| 19 | behavior | 常规对照 | ❌ 未通过 | root（词根词缀） | 2 | 0 | 107.6s |
| 20 | emotion | 常规对照 | ✅ 通过 | sound（谐音） | 1 | 0 | 34.6s |

## 逐词明细

### 1. cathedral（高踩钉子户）

- 结果：❌ 未通过（unverified）
- 新版方法：shape（字形联想）
- 耗时：generate_1 27.2s · polish_1 6.2s · judge_1 22.9s · total 132.1s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=true · is_robot_tone=false · is_logic_broken=true · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 0 · 方法选对 0 · 谐音过关 10 · 逻辑连贯 0 · 文本充足 0 · 像人说话 10
- 备注：两轮都没过评审判定，交付历史最佳（第1轮）；最后一轮问题：dral 不是熟词也不是透明词缀，用户得额外记一个无意义拼写块。；dral 在后续画面和末步里没有落点，遮住英文只能推出 cat+he 和教堂，推不出完整拼写。；作废轮次：第2轮：生成结果不是 JSON：{"method":"shape","split":["cat","he","dral"],"elements":[{"part":"cat","method":"shape","reasoning":"拼写里一眼就能拎出来的熟词块，猫的图像感极强，负责把开头三个字母焊死。"},{"part":"he","method":"shape","reasoning":"初

**新版全文**

> ① 不先管读音，拼写直接切成 cat + he + dral：一只猫、一个他，后面拖着长长一截 dral。
>
> ② 想画面：猫跟在他身后钻进一扇高得要仰头看的大木门，门后是一栋特别大的楼。
>
> ③ 楼里说话嗡嗡回声，抬头是巨大的彩色玻璃窗，一排排长椅全朝同一个方向排开，这楼就是座教堂。
>
> ④ 提醒真读音：cathedral 不读 cat-he-dral，重音在中间，整体读 /kəˈθiːdrəl/，开头的 a 发 /ə/。
>
> ⑤ 猫和他走进去都小得像尘埃的那座又高又大的楼 ——> 大教堂。

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

- 结果：✅ 通过
- 新版方法：sound（谐音）
- 耗时：generate_1 9.7s · polish_1 6.5s · judge_1 18.0s · generate_2 35.7s · polish_2 7.9s · judge_2 5.9s · total 83.7s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① obscure 真读 /əbˈskjʊr/，按音一一对上：əb=阿伯，s=死，kju=Q，r=儿，念顺了就是「阿伯死Q儿」。
>
> ② Q 是那道难题的代号：阿伯被一道 Q 难得要死，看了半天也没看明白。
>
> ③ 他急得支支吾吾，答案糊成一团雾，旁边的人听不清也看不懂。
>
> ④ 题糊、话糊、一团雾遮在眼前 ——> 晦涩的；模糊的；使模糊。

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
- 耗时：generate_1 17.6s · polish_1 3.9s · judge_1 10.1s · generate_2 23.5s · polish_2 5.6s · judge_2 13.2s · total 74.0s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① endeavor 一口气念下来像「因得佛」，国际音标 /ɪnˈdevər/：开头 in 是因，中间重音 de 是得，结尾 vor 儿化收成佛儿，三拍全对上。
>
> ② 一个和尚因为想求得一尊佛，天天翻山挑水、凿石建庙，玩命地干。
>
> ③ 为了心里那个目标拼了老命去够 ——> 努力；尽力（去做）。

**旧版**（`data/results/endeavor.json` 最后一条，method=shape（字形联想），updatedAt=2026-11-19T18:43:22+08:00）

> ① 先看字形，endeavor 打头三个字母就是 end，拿它当第一个锚点，后面剩下的一整块就是 eavor。
>
> ② 再看读音，eavor 这一块念起来跟 ever 同音，直接借 ever 的「永远」来记。
>
> ③ 把两个锚点串成一条动作链：手上一直使着劲，使到 end 那个尽头才收手；ever 的「永远」让这股劲一直不停，合起来就是「把劲一直使到尽头」。
>
> ④ 这股劲就是长时间不松手的努力，落到一件具体的事上，又成了认真的尝试、一桩要长期做的事业 ——> 努力；尽力；尝试。

### 4. monopoly（高踩钉子户）

- 结果：❌ 未通过（unverified）
- 新版方法：sound（谐音）
- 耗时：generate_1 16.4s · polish_1 3.4s · judge_1 17.6s · generate_2 39.1s · polish_2 8.2s · judge_2 14.5s · total 99.3s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=true · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 0 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10
- 备注：两轮都没过评审判定，交付历史最佳（第1轮）；最后一轮问题：「没那魄力」与 /məˈnɒpəli/ 对不上：没/mei 对 /mə/、魄/po 对 /pə/，重音也不一致，却称一个音不差。

**新版全文**

> ① monopoly 一口气念快了像「摸拿不离」：mo 摸、no 拿，尾音 po-ly 收成「不离」。
>
> ② 一个人先把全城的买卖摸清底细，再一手全拿过来，攥在手里死不撒开，别人连摆个摊的缝都插不进。
>
> ③ 全城人想买想卖，只能找他一个 ——> 垄断；专营。

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
- 新版方法：root（词根词缀）
- 耗时：generate_1 13.6s · polish_1 6.4s · judge_1 6.6s · total 26.6s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① subtle 开头就是熟脸 sub——地铁 subway、潜艇 submarine 里的 sub，意思是'在下面'。
>
> ② 中间的 b 写在词里却一声不吭，真读音是 /ˈsʌtl/；它就压在表面底下，谁也听不见。
>
> ③ 一个东西明明在那儿、却压在底下不冒头，你不低头细看就逮不着它。
>
> ④ 确实在、却藏在底下、得细看才逮得住的 ——> 微妙的；不易察觉的。

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
- 新版方法：sound（谐音）
- 耗时：generate_1 22.7s · polish_1 7.8s · judge_1 6.5s · total 37.1s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① conducive 照读音 kən-djuː-sɪv 切成 con、du、cive，三个音节连读像「肯丢媳妇」。
>
> ② 你搬家那天，他肯丢下自家媳妇，先跑来帮你抬柜子搭把手。
>
> ③ 这种肯丢下媳妇来帮衬的人一到位，活儿立马顺了 ——> 有助于……的；促成……的。

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
- 耗时：generate_1 15.1s · polish_1 8.9s · judge_1 47.6s · total 71.7s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① sub 你天天在 subway 地铁里见，地铁在地下跑，sub 就是「在下面」；sid 拼起来像你天天做的 sit「坐」，整词读 /ˈsʌbsədi/，sid 实际发 /səd/，词根还是 sed/sid 这支「坐」。
>
> ② subsidy 拆开就是「坐在下面」：一笔钱不站上台前出风头，专门坐到底下当垫子。
>
> ③ 厂子、农户快撑不住时，政府让这笔钱坐下去垫着托着 ——> 补贴；津贴。

**旧版**（`data/results/subsidy.json` 最后一条，method=root（词根词缀），updatedAt=2026-11-19T20:56:45+08:00）

> ① 先把 subsidy 拆成 sub + sid + y：sub- 是「在下、垫底」，sid 是「坐」，末尾的 -y 把它收成一个名词，指一样东西。
>
> ② 合起来，画面是一支「坐在下面」的后备部队：它不站前排，坐在阵后待命，等前方快顶不住时再起身托一把。
>
> ③ 这笔钱也一样，平时蹲在后头不出面，等某个行业或群体快撑不住，才从底下垫上去把局面稳住。
>
> ④ 所以 subsidy 就是国家或机构留在后面、从底下垫上去托住某一方的那笔钱 ——> 补贴、津贴、补助金。

### 8. adolescent（高踩钉子户）

- 结果：❌ 未通过（unverified）
- 新版方法：sound（谐音）
- 耗时：generate_1 21.3s · polish_1 7.4s · judge_1 15.0s · generate_2 76.0s · polish_2 5.5s · judge_2 20.8s · total 146.0s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=true · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 0 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10
- 备注：两轮都没过评审判定，交付历史最佳（第1轮）；最后一轮问题：第①步称 /ˌædəˈlesənt/ 有五个音节并谐成「阿弟累神特」，但该音标通常四音节，且「弟」对 /də/、「神特」对 /sənt/ 都明显偏离。

**新版全文**

> ① adolescent 连起来念快了像「爱到累神」：a-do 对上「爱到」，les 收住「累」，cent 的尾音轻轻带一个「神特」。
>
> ② 十几岁的孩子头一回谈恋爱，爱到把精神耗干，白天上课犯困，夜里兴奋得睡不着，还觉得自己已经是大人了。
>
> ③ 又爱又累、半大不小的这个年纪 ——> 青少年（的）；青春期的。

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
- 新版方法：root（词根词缀）
- 耗时：generate_1 9.9s · polish_1 15.2s · judge_1 26.4s · generate_2 16.3s · polish_2 5.9s · judge_2 10.2s · total 83.9s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① substantial 开头的 sub 你天天坐——subway 钻到地下、submarine 潜到水下，sub 就是'在下面'。
>
> ② 中间的 stan 就是熟词 stand'站'的词根，本义是站好站稳；词尾 tial 只是形容词后缀，不管意思。
>
> ③ 拼起来是'站在底下'：整栋楼压在地基上，地基就是站在底下撑住全楼的那一大坨实料。
>
> ④ 地基打得越厚，楼起得越大——底子实、分量足才配叫这个词；真读音 /səbˈstænʃəl/，tial 读'仕尔'的轻音，别照拼写硬读。
>
> ⑤ 站在底下、撑得住分量的那股实在劲儿 ——> 大量的；坚实的；实质的。

**旧版**（`data/results/substantial.json` 最后一条，method=root（词根词缀），updatedAt=2026-11-09T00:17:59+08:00）

> ① substantial 就按 sub-、stant、-ial 三块看，sub- 是在下面，stant 是站，-ial 是形容词的尾巴。
>
> ② 你想想家里的饭桌：sub- 让桌腿待在桌面底下，stant 让它稳稳站住。
>
> ③ 桌腿能在底下站住、把桌面撑起来，就不是空心摆设，得有实打实的木料和分量。
>
> ④ sub（在下）+ stant（站）+ -ial（形容词）三块一合，就是“在底下站住、把实打实的东西撑起来”的，数量、分量和影响都小不了 ——> 大量的；实质性的；重大的。

### 10. casual（高踩钉子户）

- 结果：❌ 未通过（unverified）
- 新版方法：root（词根词缀）
- 耗时：generate_1 13.6s · polish_1 6.7s · judge_1 7.6s · generate_2 23.9s · polish_2 6.0s · judge_2 11.5s · total 69.4s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=true · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 0 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10
- 备注：两轮都没过评审判定，交付历史最佳（第1轮）；最后一轮问题：casual 的 /ˈkæʒuəl/ 被硬谐成「开着哦」，「开」「着」「哦」与音标三段明显对不上。

**新版全文**

> ① casual 尾巴上的 -ual 跟 usual 是同一个模子，都是形容词尾巴「……的」，拿掉它，前头剩下 cas。
>
> ② cas 把 e 塞回去就是 case——「情况、场合」那个熟词，casual 本来就生在 case 这一家，词源不掺假。
>
> ③ 一个凡事都「看情况」的人：穿衣看场合怎么舒服怎么套，做事碰上啥算啥，从不较真安排 ——> 随便的、非正式的；偶然的。

**旧版**：无旧版记录

### 11. diminish（常规对照）

- 结果：✅ 通过
- 新版方法：root（词根词缀）
- 耗时：generate_1 12.1s · polish_1 10.5s · judge_1 33.7s · generate_2 12.7s · polish_2 10.8s · judge_2 44.1s · total 123.8s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① diminish 切成 di + mini + sh：mini 就是「迷你」，小一号；结尾 sh 抓 finish 的尾巴，把这个词甩成动作。
>
> ② 开头的 di 管方向，往下：东西被一路往下压、往小里收，像把长裙改成迷你裙。
>
> ③ 越压越迷你，个头和数量一路往下掉 ——> （使）减少；缩小。
>
> ④ 拼写按 di-mini-sh 落笔，读出来 /dɪˈmɪnɪʃ/ 正好就是真读音，不用二次纠正 ——> 减少；缩减。

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
- 耗时：generate_1 14.3s · polish_1 5.3s · judge_1 12.3s · generate_2 13.8s · polish_2 12.3s · judge_2 23.9s · total 82.0s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① random 直接切成 ran + dom：ran 是 run 的过去式，跑步的跑；dom 是 kingdom 结尾那块，专指一块地盘、一堆人。
>
> ② 一块 kingdom 的大广场上站满人，哨声一响，所有人立刻 ran，各跑各的。
>
> ③ 拼写里 ran 抬头就能看见，但真读音是 /ˈrændəm/，后半截 dom 只读轻声 /dəm/，别照 dom 原音硬念。
>
> ④ 一大群人 ran 得东一个西一个，谁落到哪个角落全凭临场，没人事先定好 ——> 随机的；任意的。

**旧版**（`data/results/random.json` 最后一条，method=shape（字形联想），updatedAt=2026-11-09T11:23:18+08:00）

> ① random 的拼写能干净地切成 ran 和 dom：ran 是 run 的过去式，先带上“跑”的动作；dom 借常见后缀 -dom 的样子，表示一片范围、地盘。
>
> ② 想象一块没有编号、没有路标的地界，一个人在上面乱跑，每一步都换方向，路线根本没有定数。
>
> ③ 路线不固定，下一步落在哪儿没法预判，这种乱跑没有预定顺序 ——> 随机；任意；胡乱地。

### 13. avoid（常规对照）

- 结果：✅ 通过
- 新版方法：sound（谐音）
- 耗时：generate_1 34.1s · polish_1 9.8s · judge_1 7.6s · total 51.5s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① avoid 读快了像「呃，我异地」：开头的 /ə/ 是含糊一声「呃」，重音砸在后头，尾音 d 正好收在「地」上。
>
> ② 碰上不想见的人、不想摊的事，先应付一声「呃」，人转身跑去异地，麻烦在原地扑空。
>
> ③ 一句「呃我异地」，人和事就此错开、谁也没碰上谁 ——> 避开；躲避；回避。

**旧版**：无旧版记录

### 14. allege（常规对照）

- 结果：❌ 未通过（unverified）
- 新版方法：sound（谐音）
- 耗时：generate_1 54.8s · polish_1 9.5s · judge_1 12.0s · generate_2 228.5s · polish_2 3.4s · judge_2 9.0s · total 317.2s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=true · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 0 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10
- 备注：两轮都没过评审判定，交付历史最佳（第1轮）；最后一轮问题：把 /lɛdʒ/ 说成「列举」不贴音：/lɛdʒ/ 是单音节且韵尾为 /dʒ/，而「列举」读 lièjǔ，谐音明显多出一截。

**新版全文**

> ① allege 一口气念快像「按了之」：开头轻读的 ə 落成「按」，重读的 ledʒ 正好收成「了之」。
>
> ② 把一句话往人身上一按，事就算完了（之）；别人追问证据，他两手一摊——按都按了，还要啥证据。
>
> ③ 不掏证据、先把话按死在人身上 ——> （无证据地）指控；声称。

**旧版**：无旧版记录

### 15. pledge（常规对照）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 34.4s · polish_1 3.6s · judge_1 22.9s · generate_2 56.3s · polish_2 3.3s · judge_2 32.8s · total 153.4s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① 把 pledge 切成 pl + edge：末尾的 edge（边缘）最扎眼，读 /edʒ/，两块拼起来就是整词真读音 /pledʒ/，一个音节，不用纠偏。
>
> ② 一个人扑（pl）到悬崖 edge 上，当众发狠誓：这事办不成，我人就从这个边上掉下去。
>
> ③ 誓言一出口，命就押在了这条边上——跟押东西一个道理，押出去的话才作数。
>
> ④ 拿命和财物作押，换回一句承诺 ——> 誓约、保证；抵押（品）。

**旧版**：无旧版记录

### 16. beforehand（常规对照）

- 结果：❌ 未通过（unverified）
- 新版方法：root（词根词缀）
- 耗时：generate_1 7.7s · polish_1 6.8s · judge_1 12.1s · generate_2 9.2s · polish_2 4.9s · judge_2 13.7s · total 54.5s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=true · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 0 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10
- 备注：两轮都没过评审判定，交付历史最佳（第1轮）；最后一轮问题：before+hand 属于拼写拆法，但只用“怎么拼就怎么念”带过，末步前未给出真实读音和音标。

**新版全文**

> ① beforehand 拆成 before + hand 两个熟词：before 是「在……之前」，hand 是「手」。
>
> ② 凡是「动手」之前就办妥的事，手都还没伸出去——像出远门，人还坐在家里，票早攥在手里了。
>
> ③ 动手之前就办完 ——> 预先；事先；提前。

**旧版**：无旧版记录

### 17. stumble（常规对照）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 94.5s · polish_1 7.4s · judge_1 33.8s · total 135.7s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① stumble 拆成 st + umble：st 是 stand、stop、stone 的头——一个人 stand 站得稳稳地往前走。
>
> ② 脚下突然踢到一块 stone 石头，这只脚猛地 stop 停住，身子还带着惯性往前冲。
>
> ③ 尾巴 umble 读 /ʌmbl/，正像被磕的人「嗯——不」地闷哼一声：脚别在石头上，一时拔不出来。
>
> ④ 站得稳稳的人被脚下的石头猛地别了一下，整个人往前栽 ——> 绊倒；跌跌撞撞地走。

**旧版**：无旧版记录

### 18. message（常规对照）

- 结果：❌ 未通过（unverified）
- 新版方法：contrast（对比辨析）
- 耗时：generate_1 27.2s · polish_1 5.6s · judge_1 12.0s · generate_2 16.1s · polish_2 3.3s · judge_2 18.7s · total 82.9s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=true · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 0 · 逻辑连贯 0 · 文本充足 0 · 像人说话 10
- 备注：两轮都没过评审判定，交付历史最佳（第1轮）；最后一轮问题：「梅西寄」按汉字实际读音是 méi-xī-jì，和 /ˈmesɪdʒ/ 的 /me-sɪ-dʒ/ 明显对不上，尤其「寄」不是 /dʒ/。

**新版全文**

> ① message 和 massage 撞脸就撞在一个 a 上：massage 中间多塞一个 a，其余字母一字不差。
>
> ② 多出来的这个 a 读音就是「啊」：massage 念「嘛啊撒起」，被按得舒服务得「啊」出一声——带 a 的那个是按摩。
>
> ③ message 抽掉这个 a，读音落回「梅斯」（/ˈmesɪdʒ/），按的那一下没了，只剩一句平平的话捎给你 ——> 消息；口信；留言。

**旧版**：无旧版记录

### 19. behavior（常规对照）

- 结果：❌ 未通过（unverified）
- 新版方法：root（词根词缀）
- 耗时：generate_1 20.6s · polish_1 3.5s · judge_1 34.6s · generate_2 14.1s · polish_2 3.4s · judge_2 31.2s · total 107.4s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=true · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 0 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10
- 备注：两轮都没过评审判定，交付历史最佳（第1轮）；最后一轮问题：be + have + ior 实际拼成 behaveior，和 behavior 对不上，拆法拼不回原词。；第二步从 be=处在、have=拿住 直接跳到“把自己拿住”，“自己”没有来源，语义链断。

**新版全文**

> ① behavior 切成 be + hav + ior：be 是"处于"，hav 就是熟词 have 的词干"拿住、持有"，ior 是名词尾巴。
>
> ② have 在这儿当"拿住自己"讲：一个人怎么把自己拿住——端着还是松着，就是他处世的样子。
>
> ③ 你是稳稳地拿住自己，还是松松垮垮随它去，全写在别人眼里，一眼就能看出来。
>
> ④ 一个人拿住自己的那副样子 ——> 行为；举止；表现。

**旧版**：无旧版记录

### 20. emotion（常规对照）

- 结果：✅ 通过
- 新版方法：sound（谐音）
- 耗时：generate_1 18.0s · polish_1 7.3s · judge_1 9.1s · total 34.5s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① emotion 一口气念下来像「一摸神」：开头的 e 只发短音 i，算「一」，中间重读的 mo 是「摸」，末尾的 -tion 缩成 shen，正好收在「神」。
>
> ② 你伸手一摸，摸到人心里住着的那尊小神，它当场被惊动：脸红了，心跳快了，想哭想笑全压不住。
>
> ③ 心里的东西一被摸到就整锅翻涌起来 ——> 情感；情绪。

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
