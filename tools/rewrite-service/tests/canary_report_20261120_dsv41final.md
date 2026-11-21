# 20 词真实金丝雀压测报告（2026-11-20）

## 汇总

- 词数：20（高踩钉子户 10 / 常规对照 10）
- 通过率：**19 / 20（95.0%）**；高踩钉子户 9/10，常规对照 10/10
- 失败词：obscure
- 总耗时：**38 分 15 秒**（逐词耗时合计 2295.1s，平均 114.8s/词；整轮墙钟 38 分 15 秒）
- 硬拦截总次数：**0 次**（0 个词命中，命中条目共 0 条）
- 总 attempts：25（hard.check 24 次 / judge 24 次；obscure 有 attempt 未进入 judge（如生成非 JSON））
- 排雷各项 true 次数（每词最后一次 judge，共 20/20 词有 judge 结果）：has_unknown_part 0 · is_robot_tone 0 · is_logic_broken 0 · phonetic_drift_unwarned 1 · violates_user_intent 0
- 排雷缺键（null）次数：violates_user_intent 6
- 说明：6 个词最后一次 judge 未输出 violates_user_intent（无用户原话时该题不适用，judge() 解析为 null；当前 `_passed` 已兼容 None，其余四项仍须严格 false）。
- 生成模型：ds-v41-flash（GLM 5.3）
- Judge：providers.judge_target 自动选择 → wb/cn:glm-5.3
- 日期：2026-11-20
- 环境：REWRITE_DATA_DIR=tests\_canary_data_20261120；严格串行，无并发；单次上限 600 秒
- 脚本：`python tests/canary_20.py`（tests\canary_20.py）

| # | 词 | 类别 | 结果 | 新版方法 | attempts | 硬拦截 | 耗时 |
|---|---|---|---|---|---|---|---|
| 1 | cathedral | 高踩钉子户 | ✅ 通过 | shape（字形联想） | 1 | 0 | 121.5s |
| 2 | obscure | 高踩钉子户 | ❌ 未通过 | sound（谐音） | 2 | 0 | 162.4s |
| 3 | endeavor | 高踩钉子户 | ✅ 通过 | shape（字形联想） | 2 | 0 | 174.8s |
| 4 | monopoly | 高踩钉子户 | ✅ 通过 | sound（谐音） | 1 | 0 | 111.0s |
| 5 | subtle | 高踩钉子户 | ✅ 通过 | shape（字形联想） | 1 | 0 | 48.6s |
| 6 | conducive | 高踩钉子户 | ✅ 通过 | root（词根词缀） | 1 | 0 | 57.8s |
| 7 | subsidy | 高踩钉子户 | ✅ 通过 | shape（字形联想） | 1 | 0 | 110.0s |
| 8 | adolescent | 高踩钉子户 | ✅ 通过 | shape（字形联想） | 1 | 0 | 152.9s |
| 9 | substantial | 高踩钉子户 | ✅ 通过 | root（词根词缀） | 1 | 0 | 63.6s |
| 10 | casual | 高踩钉子户 | ✅ 通过 | root（词根词缀） | 2 | 0 | 198.1s |
| 11 | diminish | 常规对照 | ✅ 通过 | root（词根词缀） | 1 | 0 | 122.3s |
| 12 | random | 常规对照 | ✅ 通过 | shape（字形联想） | 1 | 0 | 67.5s |
| 13 | avoid | 常规对照 | ✅ 通过 | sound（谐音） | 1 | 0 | 138.3s |
| 14 | allege | 常规对照 | ✅ 通过 | shape（字形联想） | 1 | 0 | 121.3s |
| 15 | pledge | 常规对照 | ✅ 通过 | shape（字形联想） | 1 | 0 | 81.0s |
| 16 | beforehand | 常规对照 | ✅ 通过 | root（词根词缀） | 2 | 0 | 53.8s |
| 17 | stumble | 常规对照 | ✅ 通过 | sound（谐音） | 2 | 0 | 247.7s |
| 18 | message | 常规对照 | ✅ 通过 | shape（字形联想） | 1 | 0 | 91.0s |
| 19 | behavior | 常规对照 | ✅ 通过 | shape（字形联想） | 1 | 0 | 63.4s |
| 20 | emotion | 常规对照 | ✅ 通过 | root（词根词缀） | 1 | 0 | 108.1s |

## 逐词明细

### 1. cathedral（高踩钉子户）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 73.6s · polish_1 32.1s · judge_1 15.6s · total 121.3s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① cat 是猫，he 是他，最后一块 dral 顺口收成教堂高高的尖顶，三块按 cat-he-dral 排好。
>
> ② 一只猫蹲着，他抬头看那尖顶，猫和人在同一座大建筑前缩成两个小点。
>
> ③ 那种高到让人抬头看、里面一排排座位的教堂就是 cathedral，拼写按 cat-he-dral 记，真读音是 /kəˈθiːdrəl/，别照字母硬念 ——> 大教堂。

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
- 新版方法：sound（谐音）
- 耗时：generate_1 74.1s · polish_1 21.8s · judge_1 31.6s · total 162.1s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=true · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 0 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10
- 备注：两轮都没过评审判定，交付历史最佳（第1轮）；最后一轮问题：末段谐音「熟」（/ʂu/）无法覆盖 /skjʊr/ 里的 /k/、/j/ 滑音和卷舌 /r/，与国际音标逐段对不上；作废轮次：第2轮：生成结果不是 JSON：{"method":"shape","split":["obs","cure"],"elements":[{"part":"obs","method":"shape","reasoning":"开头三个字母认不出任何熟词，像是多出来的一层，紧紧贴在 cure 前面，把后面的部分挡住了。"},{"part":"cure","method":"shape","reaso

**新版全文**

> ① obscure 读 /əbˈskjʊr/，快念时像「俺不熟」：一个人或一件事，俺压根不熟悉。
>
> ② 不熟的东西摆在眼前，像隔了一层雾，轮廓看不清，意思也听不明白。
>
> ③ 看不清、听不明，话就晦涩难懂，真相也被遮在后面 ——> 晦涩的；使模糊。

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
- 新版方法：shape（字形联想）
- 耗时：generate_1 76.4s · polish_1 16.5s · judge_1 12.5s · generate_2 45.6s · polish_2 19.4s · judge_2 4.2s · total 174.6s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① endeavor 开头就是 end（终点），我在脑子里把“跑到终点”定成目标。
>
> ② 后面 eavor 里的 a、o 轻轻带过，连起来听着像 ever（永远），我拿它记“永远不松劲”。
>
> ③ 我张口念一遍，endeavor 实际读 /ɪnˈdevər/，重音在 dev，开头是 /ɪn/，所以别照着 end 念。
>
> ④ 把 end（终点）和 ever（永远）合起来，就是心里永远盯着终点、一路使到底 ——> 努力；尽力去做。

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
- 耗时：generate_1 66.0s · polish_1 27.3s · judge_1 17.5s · total 110.8s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① monopoly 读 /məˈnɒpəli/，快念就是「莫拿薄利」：mə 莫、nɒ 拿、pə 薄、li 利。
>
> ② 他堵在街口对同行说「莫拿薄利」，连一分小利都不许别人伸手。
>
> ③ 他转过身，把整条街的货源、客人和价钱全攥在自己手里，只许自己卖。
>
> ④ 街上的同行连薄利都拿不到，买卖只归他一人 ——> 垄断；专营。

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
- 耗时：generate_1 27.6s · polish_1 14.4s · judge_1 6.4s · total 48.4s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① subtle 拆成 sub + tle：sub 你熟，先把 sub 当作“在下面”的盖子扣上去，tle 跟在盖子后面。
>
> ② 可它真读 /ˈsʌtl/，sub 末尾的 b 不出声，像盖子底下藏着东西，露不出头。
>
> ③ 盖子底下藏着的这层差别只轻轻带过，不凑近就看不出来 ——> 微妙的；不易察觉的；巧妙的。

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
- 耗时：generate_1 18.4s · polish_1 32.7s · judge_1 6.5s · total 57.6s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① con 在 connect 里是“凑到一起”，duc 在 produce、reduce 里是“带、引”，-ive 是“……的”；conducive 英读 /kənˈdjuːsɪv/、美读 /kənˈduːsɪv/，duc 这一截不读 /dʌk/。
>
> ② 把 con + duc + ive 三块拼起来，就是“把力量一起带过去、引到某件事上”的。
>
> ③ 你把条件、力气一起带到目标上，就是给它添一把力，让它更容易办成。
>
> ④ 能这样添一把力的 ——> 有助于……的；有益于……的。

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
- 新版方法：shape（字形联想）
- 耗时：generate_1 84.4s · polish_1 17.7s · judge_1 7.7s · total 109.8s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① subsidy 拆成 sub + si + dy 三块：sub 是垫在下面的「替补、下位」，si 记成「四」，dy 只当尾巴。
>
> ② 政府先垫在下面当替补，替老百姓那「四」样花销：钱、粮、房、药，垫完再往上补。
>
> ③ 垫在下面的那笔钱从下往上送，兜住底，不让人掉下去。
>
> ④ 真读音是 /ˈsʌbsədi/，中间的 si 别读成「四」的音，只当拼写记忆块 ——> 补贴；津贴。

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
- 耗时：generate_1 76.9s · polish_1 54.0s · judge_1 21.8s · total 152.7s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① ado 念成 a-do“啊兜”，口袋里揣着心事，也没几块钱，这样的半大少年正是这词要指的人。
>
> ② les 读起来像“累死”，这个阶段的人动不动喊累、嫌烦、闹别扭，情绪冲得很。
>
> ③ cent 是我们熟的“分、一分钱”，兜里空，钱还得一分一分数着花，合起来就是还没挣钱的半大孩子；真读音是 /ˌædəˈlesənt/，别按 ado-les-cent 硬念。
>
> ④ 兜里没钱、整天喊累、一分一分数着花，这几块拼到半大孩子身上 ——> 青春期的；青少年。

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
- 耗时：generate_1 42.8s · polish_1 15.9s · judge_1 4.6s · total 63.4s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① sub 是“在下面”，像 subway 在地下跑，我先把 substantial 压到“底下”。
>
> ② stant 跟 stand 只差最后一个字母，d 换成 t，还是“站住、撑住”。
>
> ③ ial 是形容词尾巴，给“站住撑住”戴顶帽子；这个词真读 /səbˈstænʃəl/，stant 末尾的 t 和 ial 连成 /ʃəl/，别按字母一个个硬念。
>
> ④ 底下有东西稳稳站住撑住，就不是空壳：有实体、结实，实打实的分量也大 ——> 实质的；坚固的；大量的；重要的。

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
- 新版方法：root（词根词缀）
- 耗时：generate_1 75.9s · polish_1 12.0s · judge_1 55.2s · generate_2 13.1s · polish_2 37.4s · judge_2 4.2s · total 197.9s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① casual 拆成 cas + ual，读 /ˈkæʒuəl/，别按拼写硬拼：case 去掉末尾 e 剩 cas，接上 usual 尾巴上的 -ual。
>
> ② case 就是「情况」：今天撞上哪件事、摊上什么局面，事前谁也不知道。
>
> ③ 撞上什么算什么，事前没安排，事情就是偶然来的，人跟着不讲究、不定规矩。
>
> ④ 人穿衣不挑、说话不挑、见人不挑场合 ——> 随便的；非正式的；偶然的。

**旧版**：无旧版记录

### 11. diminish（常规对照）

- 结果：✅ 通过
- 新版方法：root（词根词缀）
- 耗时：generate_1 73.8s · polish_1 45.1s · judge_1 3.2s · total 122.1s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① mini 你早就认识：迷你裙、迷你车，它就是「小」。
>
> ② diminish 拆成 di、mini、sh，mini 卡在正中间，读 /dɪˈmɪnɪʃ/ 时重音落在第二音节，也就是 mini 开头那个 min 上，嘴里念到这儿就撞见「小」。
>
> ③ 把东西朝 mini 那头收，它就一点点变小、变少。
>
> ④ 小下去、少下去，力量也跟着缩水 ——> 减少；缩小；削弱。

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
- 耗时：generate_1 43.3s · polish_1 19.1s · judge_1 4.9s · total 67.3s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① random 按拼写切成 ran-dom，真读音是 /ˈrændəm/，第二个音节 dom 轻读成 /dəm/，别照着字母重念。
>
> ② ran 是 run 的过去式，我先摆出一个已经跑出去、脚下没画固定路线的人。
>
> ③ dom 借 freedom 的结尾记成“状态、境地”，两段并起来，就是人处在乱跑的状态。
>
> ④ 跑起来不挑方向，落到哪儿全看下一步 ——> 随机的；随意的。

**旧版**（`data/results/random.json` 最后一条，method=shape（字形联想），updatedAt=2026-11-09T11:23:18+08:00）

> ① random 的拼写能干净地切成 ran 和 dom：ran 是 run 的过去式，先带上“跑”的动作；dom 借常见后缀 -dom 的样子，表示一片范围、地盘。
>
> ② 想象一块没有编号、没有路标的地界，一个人在上面乱跑，每一步都换方向，路线根本没有定数。
>
> ③ 路线不固定，下一步落在哪儿没法预判，这种乱跑没有预定顺序 ——> 随机；任意；胡乱地。

### 13. avoid（常规对照）

- 结果：✅ 通过
- 新版方法：sound（谐音）
- 耗时：generate_1 78.7s · polish_1 39.6s · judge_1 19.8s · total 138.1s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① avoid 读 /əˈvɔɪd/，a 这一截先「呃」一下停住，void 这一截撞上中文「勿的」，「勿」就是不要。
>
> ② 连起来就是先「呃」一声卡住，再听清「勿的」，像走到门口被拦住，有人甩一句「别进去」。
>
> ③ 耳朵一接住「勿的」，脚就收住，转身绕开那扇门，不往那件事上靠。
>
> ④ 你主动绕开、不去碰那件事 ——> 避免；躲开。

**旧版**：无旧版记录

### 14. allege（常规对照）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 80.8s · polish_1 18.8s · judge_1 21.6s · total 121.1s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① allege 拆成 a + lle + ge，开头的 a 我先『啊』一声，把话头挑起来。
>
> ② 中间 lle 是两个 l 夹着 e，两个 l 一左一右立着，把 e 夹成细长一条，我拿它记一长串话拖着走。
>
> ③ 结尾 ge 轻轻一收，一长串话最后落停，真读音是 /əˈledʒ/，ge 念『治』不念『哥』。
>
> ④ 先『啊』一声，再把一长串话拉出来，最后落停收住，一张嘴把一件事咬定 ——> 声称；断言（尤指未经证实地指控）。

**旧版**：无旧版记录

### 15. pledge（常规对照）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 64.8s · polish_1 10.7s · judge_1 5.4s · total 80.9s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① pledge 拆成 ple + dge：ple 是 please（请）的前三个字母，dge 是 judge（法官）的后三个字母。
>
> ② 脑子里摆一个法庭：法官抬手说一声“Please”，示意你把手举起来，当庭开口。
>
> ③ 这个词的真读音是 /pledʒ/，要一口气收住，不能按 ple/dge 分开念；你开口说的不是闲话，是拿信用作押。
>
> ④ 在法官面前把话押上、说错要担责的那一句 ——> 保证；誓言；抵押。

**旧版**：无旧版记录

### 16. beforehand（常规对照）

- 结果：✅ 通过
- 新版方法：root（词根词缀）
- 耗时：generate_1 9.0s · polish_1 12.2s · judge_1 5.9s · generate_2 16.5s · polish_2 6.5s · judge_2 3.6s · total 53.7s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① before 是熟词“在……之前”，hand 是熟词“手”，beforehand 拆成 before + hand 两块。
>
> ② 事情还没开始，手先伸出去准备，这个动作就是“在发生之前先着手”。
>
> ③ beforehand 别按 before 和 hand 两个词重重念，它的真读音是 /bɪˈfɔːhænd/，前半 before 读得轻短。
>
> ④ 在事情发生前先动手备好 ——> 预先；提前。

**旧版**：无旧版记录

### 17. stumble（常规对照）

- 结果：✅ 通过
- 新版方法：sound（谐音）
- 耗时：generate_1 78.6s · polish_1 58.0s · judge_1 17.9s · generate_2 77.7s · polish_2 11.7s · judge_2 3.7s · total 247.6s
- attempts：2
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① stumble 读 /ˈstʌmbl/，快念出来像「死塌步」，死、塌、步三个音一口气压下来。
>
> ② 走路时脚下一软，人往下塌，硬生生迈出那一步就叫「塌步」。
>
> ③ 脚被绊住、身子塌下去一步，话也跟着卡壳。
>
> ④ 走路塌步、说话卡壳 ——> 绊倒；踉跄；结结巴巴地说。

**旧版**：无旧版记录

### 18. message（常规对照）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 48.9s · polish_1 38.2s · judge_1 3.8s · total 90.9s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① message 就拆 mess + age，mess 是“一团乱”，age 是“时代、一段时间”，两个都是眼熟的短词。
>
> ② 到了没有手机的 age，人要传话，就把话揉成一小团乱字（mess），托人捎过去。
>
> ③ 它不读“mess-age”，真读音是 /ˈmesɪdʒ/，后半截只轻轻收成 /ɪdʒ/。
>
> ④ 那团跨过一段路才到你手上的字，就是别人捎来的话 ——> 消息；信息。

**旧版**：无旧版记录

### 19. behavior（常规对照）

- 结果：✅ 通过
- 新版方法：shape（字形联想）
- 耗时：generate_1 49.8s · polish_1 6.8s · judge_1 6.7s · total 63.3s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=null
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① behavior 切成 be + hav + ior：be 就是初中学过的“是”，hav 是 have（有）去掉词尾 e 的骨架。
>
> ② 把“是”和“有”连起来问：一个人“是”什么样、“有”什么动作，这两问都落到他的表现上。
>
> ③ 别按 be 和 have 两个单词念，behavior 整体读 /bɪˈheɪvjər/：be 弱成 /bɪ/，hav 读 /heɪv/，ior 收 /jər/。
>
> ④ ior 把“是”的样子和“有”的动作收成一个名词，指别人看到的举止 ——> 行为；举止；表现。

**旧版**：无旧版记录

### 20. emotion（常规对照）

- 结果：✅ 通过
- 新版方法：root（词根词缀）
- 耗时：generate_1 53.6s · polish_1 46.9s · judge_1 7.4s · total 108.0s
- attempts：1
- 硬拦截：0 次
- 排雷：has_unknown_part=false · is_robot_tone=false · is_logic_broken=false · phonetic_drift_unwarned=false · violates_user_intent=false
- scores：拆分成立 10 · 方法选对 10 · 谐音过关 10 · 逻辑连贯 10 · 文本充足 10 · 像人说话 10

**新版全文**

> ① emotion 拆成 e + motion，读 /ɪˈmoʊʃən/；后半截 motion 念 /ˈmoʊʃən/，motor 是发动机，motion 就是转起来、走起来的那股劲。
>
> ② 开头这个 e 只发 /ɪ/，是 ex- 的简写，管“向外”；exit 出口标志里的 ex- 也指往外走。
>
> ③ 心里本来平平静静，突然有股东西从里面往外动、往外涌，喜、怒、怕都是这样冒出来。
>
> ④ 这股从心里往外涌动的劲 ——> 情绪；情感。

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
