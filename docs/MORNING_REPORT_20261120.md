# wordhelper 1.4.8 晨间交付报告（2026-11-20）

## 0. 结论

- 分支：`feat/v8-fsrs-human-os`，基线 `d315fa6`，代码 HEAD `2d13d1e`；**未 push，未动 main**。
- 版本：`1.4.8` / versionCode `23`；Debug APK `app-debug.apk`，33,980,739 B，SHA256 `7394789C812605F5CE3C08F82907DE12D78D29F26E7D383E113DC183401EFA31`。
- Android JVM 单测：**34 suites / 212 tests / 0 failures / 0 errors / 0 skipped**；新增 `Migration23To24Test` 在 w3test(AVD) Android 14 上 **1 test passed**。
- 后端单测：`python -m unittest discover -s tests -p "test_*.py"` **158 tests OK**（7.934s）。
- 20 词金丝雀（ds-v41-flash）：**19/20（95.0%）**；高踩钉子户 9/10，常规对照 10/10；失败：obscure。
- 视觉验收：设置页 90%/94% 与统计页 FSRS 卡/热力图 PASS；卡背正文可滚动，曾尝试的滚动提示因遮挡正文已移除（不作为交付功能）。

## 1. 分支与提交

分支 `feat/v8-fsrs-human-os` 基于 `d315fa6`（1.4.7v7-bugfix）。代码提交（不含本报告与 canary 证据的文档提交）：

| # | commit | 说明 |
|---:|---|---|
| 1 | `2d13d1e` | revert: 移除未生效的卡背滚动提示 |
| 2 | `fa910a7` | fix: 卡背滚动提示 + 拼写拆法强制补真读音 |
| 3 | `ade4a65` | chore: 忽略金丝雀临时数据目录 |
| 4 | `37c030e` | fix(android): 热力图空桶用中性灰，避免全绿误导 |
| 5 | `d0d1284` | merge: 统计页 FSRS 记忆状态与热力图 |
| 6 | `61e2f64` | feat(android): 统计页展示 FSRS-5 记忆状态与热力图 |
| 7 | `75d789d` | merge: 无用户原话时 violates_user_intent 视为不适用 |
| 8 | `745e561` | fix(rewrite): 无用户原话时 violates_user_intent 视为不适用 |
| 9 | `f938e3c` | merge: v8-backend 日志编码安全修复 |
| 10 | `51a3766` | fix(rewrite): 日志兼容 GBK 控制台的 IPA 字符 |
| 11 | `9e232a6` | merge: 版本升到 1.4.8 / vc23 |
| 12 | `785a5af` | chore(release): 升到 1.4.8 / vc23 |
| 13 | `7fb4052` | merge: 后端聪明室友 Prompt、硬拦截二值 Judge 与跨词自进化 |
| 14 | `58c0378` | feat(rewrite): 聪明室友 prompt + 硬拦截二值 judge + 跨词自进化 |
| 15 | `f6efb54` | merge: App 卡背 Prompt 与 Formatter 对齐聪明室友 |
| 16 | `8cec712` | merge: Android FSRS 接入与遗忘换招闭环 |
| 17 | `755be61` | fix(android): 修正 FSRS 优先级合并、开关约束与信号幂等键 |
| 18 | `837c0cb` | test(ai): 卡背缓存断言适配新格式 |
| 19 | `8db892c` | merge(fsrs): 合入 FSRS-5 调度器与 DB23→24 迁移 |
| 20 | `cf4defd` | feat(android): 接入 FSRS-5 与遗忘自动换招闭环 |
| 21 | `072e48e` | feat(fsrs): 实现 FSRS-5 调度与 DB23→24 迁移 |
| 22 | `d02a37e` | feat(prompt): 卡背模板对齐聪明室友说人话 |

改动文件清单（`git diff --name-status d315fa6..HEAD`）：

```text
M	.gitignore
M	app/src_repo/app/build.gradle.kts
A	app/src_repo/app/src/androidTest/java/com/kaoyan/wordhelper/data/database/Migration23To24Test.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/data/dao/RewriteQueueDao.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/data/database/AppDatabase.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/data/entity/Progress.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/data/entity/RewriteQueueItem.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/data/repository/RewriteRepository.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/data/repository/SettingsRepository.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/data/repository/WordRepository.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/ml/ui/MemoryHeatmapView.kt
A	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/ui/component/FsrsStatsCard.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/ui/screen/AILabScreen.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/ui/screen/LearningScreen.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/ui/screen/StatsScreen.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/ui/viewmodel/AILabViewModel.kt
A	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/ui/viewmodel/FsrsInsights.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/ui/viewmodel/LearningViewModel.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/ui/viewmodel/StatsViewModel.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/util/AIContentFormatter.kt
A	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/util/FsrsScheduler.kt
M	app/src_repo/app/src/main/java/com/kaoyan/wordhelper/util/PromptTemplates.kt
M	app/src_repo/app/src/test/java/com/kaoyan/wordhelper/data/repository/AIRepositoryTest.kt
A	app/src_repo/app/src/test/java/com/kaoyan/wordhelper/ui/viewmodel/FsrsInsightsTest.kt
M	app/src_repo/app/src/test/java/com/kaoyan/wordhelper/util/AIContentFormatterTest.kt
A	app/src_repo/app/src/test/java/com/kaoyan/wordhelper/util/FsrsSchedulerTest.kt
M	app/src_repo/app/src/test/java/com/kaoyan/wordhelper/util/PromptTemplatesTest.kt
A	tools/rewrite-service/exemplars.py
M	tools/rewrite-service/pipeline.py
A	tools/rewrite-service/profile.py
M	tools/rewrite-service/prompts/generate.txt
M	tools/rewrite-service/prompts/judge.txt
M	tools/rewrite-service/prompts/polish.txt
A	tools/rewrite-service/rules_hard.py
M	tools/rewrite-service/service.py
M	tools/rewrite-service/store.py
A	tools/rewrite-service/tests/test_binary_pass.py
A	tools/rewrite-service/tests/test_exemplars.py
M	tools/rewrite-service/tests/test_feedback.py
A	tools/rewrite-service/tests/test_feedback_profile.py
A	tools/rewrite-service/tests/test_memory_signal.py
A	tools/rewrite-service/tests/test_phonetic_warning.py
A	tools/rewrite-service/tests/test_profile.py
A	tools/rewrite-service/tests/test_rules_hard.py
```

## 2. 本次实现

- Phase 1：`tools/rewrite-service/prompts/generate.txt`、`polish.txt`、`judge.txt` 全部改为“聪明室友 / 初高中熟词基线 / 一招打穿 / 说人话禁报幕”；新增 `rules_hard.py` 确定性硬拦截；Judge 改为五道二值排雷（`has_unknown_part`、`is_robot_tone`、`is_logic_broken`、`phonetic_drift_unwarned`、`violates_user_intent`）+ `intent/same_method/history_repeat`，保留 `scores` 兼容字段。
- Phase 1 App：`PromptTemplates.kt` 的 `buildMemoryAidPrompt` 与 `AIContentFormatter.normalizeMemoryAid` 对齐新模板；`AIRepositoryTest` 旧断言已改为包含 `retry-success` 且不含旧三段标题。
- Phase 2：新增 `profile.py`（六招 Beta 先验 + Thompson Sampling + 反馈/记忆信号更新）、`exemplars.py`（黄金赞池/避雷踩池 + 动态 few-shot）、`data/profile.json` / `data/exemplars.jsonl`（运行时生成并 gitignore）；`service.py` 挂 `POST /rewrite/memory-signal`，`store.py` 增加 `memory_signals.json` 幂等记录。
- Phase 3：新增 `FsrsScheduler.kt`（FSRS-5，19 权重、DSR、NEW/LEARNING/REVIEW/RELEARNING）；`Progress` 增加 `fsrsStability/fsrsDifficulty/fsrsLapses/fsrsState`；`AppDatabase` 升到 24 并新增 `MIGRATION_23_24`；`WordRepository` 接入 FSRS 分支并保留 SM-2+ML 兼容；`LearningViewModel` 落库后发送 retained/lapsed 信号并按 `lapses>=2 || difficulty>=8.5` 自动低优先级换招；`RewriteQueueItem` 增加 `priority`，`getOpen()` 按 `priority ASC, updated_at ASC`。
- Phase 3 UI：`SettingsRepository` 增加 `fsrsEnabled`（默认 true）与 `desiredRetention`（默认 0.90，夹 0.80..0.97）；`AILabScreen` 增加 `lab_fsrs_switch` 与 `lab_retention_90/94`；统计页新增 `FsrsStatsCard` 与 `stats_fsrs_heatmap`，空桶中性灰，只给有记录桶着色。

## 3. 20 词金丝雀

- 模型：`ds-v41-flash`；Judge：`wb/cn:glm-5.3`；数据目录 `tests\_canary_data_20261120`；总耗时 `38.3` 分钟（逐词合计）。
- 通过率按“每词最后一次 judge 的五项二值全 false 且 history_repeat=false”统计；硬拦截 0 次，错误/超时 0 次。

| # | 词 | 类别 | 结果 | 方法 | attempts | 耗时 |
|---:|---|---|---|---|---:|---:|
| 1 | cathedral | 高踩钉子户 | PASS | shape | 1 | 121.3s |
| 2 | obscure | 高踩钉子户 | FAIL | sound | 2 | 162.1s |
| 3 | endeavor | 高踩钉子户 | PASS | shape | 2 | 174.6s |
| 4 | monopoly | 高踩钉子户 | PASS | sound | 1 | 110.8s |
| 5 | subtle | 高踩钉子户 | PASS | shape | 1 | 48.4s |
| 6 | conducive | 高踩钉子户 | PASS | root | 1 | 57.6s |
| 7 | subsidy | 高踩钉子户 | PASS | shape | 1 | 109.8s |
| 8 | adolescent | 高踩钉子户 | PASS | shape | 1 | 152.7s |
| 9 | substantial | 高踩钉子户 | PASS | root | 1 | 63.4s |
| 10 | casual | 高踩钉子户 | PASS | root | 2 | 197.9s |
| 11 | diminish | 常规对照 | PASS | root | 1 | 122.1s |
| 12 | random | 常规对照 | PASS | shape | 1 | 67.3s |
| 13 | avoid | 常规对照 | PASS | sound | 1 | 138.1s |
| 14 | allege | 常规对照 | PASS | shape | 1 | 121.1s |
| 15 | pledge | 常规对照 | PASS | shape | 1 | 80.9s |
| 16 | beforehand | 常规对照 | PASS | root | 2 | 53.7s |
| 17 | stumble | 常规对照 | PASS | sound | 2 | 247.6s |
| 18 | message | 常规对照 | PASS | shape | 1 | 90.9s |
| 19 | behavior | 常规对照 | PASS | shape | 1 | 63.3s |
| 20 | emotion | 常规对照 | PASS | root | 1 | 108.0s |

失败说明：

- `obscure`：两轮都没过评审判定，交付历史最佳（第1轮）；最后一轮问题：末段谐音「熟」（/ʂu/）无法覆盖 /skjʊr/ 里的 /k/、/j/ 滑音和卷舌 /r/，与国际音标逐段对不上；作废轮次：第2轮：生成结果不是 JSON：{"method":"shape","split":["obs","cure"],"elements":[{"part":"obs","method":"shape","reasoning":"开头三个字母认不出任何熟词，像是多出来的一层，紧紧贴在 cure 前面，把后面的部分挡住了。"},{"part":"cure","method":"shape","reaso

### 3.1 精选 5 组新旧文案

#### cathedral

新版：

> ① cat 是猫，he 是他，最后一块 dral 顺口收成教堂高高的尖顶，三块按 cat-he-dral 排好。
> ② 一只猫蹲着，他抬头看那尖顶，猫和人在同一座大建筑前缩成两个小点。
> ③ 那种高到让人抬头看、里面一排排座位的教堂就是 cathedral，拼写按 cat-he-dral 记，真读音是 /kəˈθiːdrəl/，别照字母硬念 ——> 大教堂。

旧版：

> ① 先看拼写，cathedral 分成 cat | he | dral 三块，每块都当成教堂的构件来认，不靠读音也不靠词根。
> ② cat 这块别念成“猫”，只取 C、A、T 的形状：C 像半圆拱，A 像尖顶，T 像十字架，正立面先搭起来。
> ③ he 接在 cat 后面：H 像两座钟塔，E 像一排窗格，把正立面一路带进中殿。
> ④ dral 收尾：D 像门洞，R 像玫瑰窗，A 像尖拱，L 像扶壁，门窗和支撑就补齐了。
> ⑤ C、A、T 搭出正立面，H、E 接上双塔和窗格，D、R、A、L 补齐门洞、玫瑰窗、尖拱和扶壁，一座主教座堂就在拼写里立起来 ——> adj. 大教堂的；主教座堂的；n. 大教堂。

#### monopoly

新版：

> ① monopoly 读 /məˈnɒpəli/，快念就是「莫拿薄利」：mə 莫、nɒ 拿、pə 薄、li 利。
> ② 他堵在街口对同行说「莫拿薄利」，连一分小利都不许别人伸手。
> ③ 他转过身，把整条街的货源、客人和价钱全攥在自己手里，只许自己卖。
> ④ 街上的同行连薄利都拿不到，买卖只归他一人 ——> 垄断；专营。

旧版：

> ① monopoly 读 /məˈnɒpəli/，顺着读音切成 mə、nɒ、pə、li 四段，每段配一个汉字。
> ② 四段逐一对过去：mə 是「么」，nɒ 是「诺」，pə 是「婆」，li 是「利」，连起来就是「么诺婆利」。
> ③ 把这四个字摊成一幅画：整条街只有这位婆子点头（诺）才算数，别家想开张，她不点头就开不成。
> ④ 进货、定价、利润（利）全攥在她一个人手里，外人挤不进来，这条街的生意只归她一家 ——> 垄断；独占；专营；专卖权；独占事业。

#### subtle

新版：

> ① subtle 拆成 sub + tle：sub 你熟，先把 sub 当作“在下面”的盖子扣上去，tle 跟在盖子后面。
> ② 可它真读 /ˈsʌtl/，sub 末尾的 b 不出声，像盖子底下藏着东西，露不出头。
> ③ 盖子底下藏着的这层差别只轻轻带过，不凑近就看不出来 ——> 微妙的；不易察觉的；巧妙的。

旧版：

> ① 先把 subtle 拆成 sub 和 tle 两截：sub 是“在下面”，tle 来自拉丁语 tela“网、织物”，到英语里磨短了，老底子讲的是织得细密。
> ② 脑子里挂一张网：线越细，网眼越密，远看像什么都没有，得凑到眼前才看得出纹路。
> ③ 这张网藏在“下面”，两边一合，就是“细密而不露”；这股劲儿碰到差别、变化，就是细微、微妙、不易察觉。
> ④ 同一股细劲儿用在眼睛和耳朵上，是能捏住细线的敏锐；用在藏起来的心思上，是狡猾；用在空气里，是细而少的稀薄。
> ⑤ 所以别把它当成“量少”，它抓的是“细密而不露” ——> 微妙的；细微的；不易察觉的；敏锐的；狡猾的；稀薄的。

#### conducive

新版：

> ① con 在 connect 里是“凑到一起”，duc 在 produce、reduce 里是“带、引”，-ive 是“……的”；conducive 英读 /kənˈdjuːsɪv/、美读 /kənˈduːsɪv/，duc 这一截不读 /dʌk/。
> ② 把 con + duc + ive 三块拼起来，就是“把力量一起带过去、引到某件事上”的。
> ③ 你把条件、力气一起带到目标上，就是给它添一把力，让它更容易办成。
> ④ 能这样添一把力的 ——> 有助于……的；有益于……的。

旧版：

> ① 先分清两个长得像的词：conducive 和 conductive 只差一个 t，那个 t 属于 conduct 的「传导」；导电导热跟这里没关系，先划掉。
> ② 划掉 t 以后，剩下的 conducive 拆成 con- + duc + -ive：con- 是「一起」，duc 是「引导、带」，-ive 是形容词的尾巴。
> ③ 这里的「引导」是把条件往结果上引：几股条件一起汇到同一个地方，那个结果就更容易出现，也就是共同促成。
> ④ 结尾的 -ive 把这股「一起往结果上带」的劲收成一个形容词，安到条件、环境头上 ——> 有益的；有助于的；有益于的；助长的。

#### random

新版：

> ① random 按拼写切成 ran-dom，真读音是 /ˈrændəm/，第二个音节 dom 轻读成 /dəm/，别照着字母重念。
> ② ran 是 run 的过去式，我先摆出一个已经跑出去、脚下没画固定路线的人。
> ③ dom 借 freedom 的结尾记成“状态、境地”，两段并起来，就是人处在乱跑的状态。
> ④ 跑起来不挑方向，落到哪儿全看下一步 ——> 随机的；随意的。

旧版：

> ① random 的拼写能干净地切成 ran 和 dom：ran 是 run 的过去式，先带上“跑”的动作；dom 借常见后缀 -dom 的样子，表示一片范围、地盘。
> ② 想象一块没有编号、没有路标的地界，一个人在上面乱跑，每一步都换方向，路线根本没有定数。
> ③ 路线不固定，下一步落在哪儿没法预判，这种乱跑没有预定顺序 ——> 随机；任意；胡乱地。

## 4. APK 与视觉验收

- APK：`D:\deepseek harness\work_anki_enligish\app\src_repo\app\build\outputs\apk\debug\app-debug.apk`
- 大小：33,980,739 B；SHA256：`7394789C812605F5CE3C08F82907DE12D78D29F26E7D383E113DC183401EFA31`；versionCode 23 / versionName 1.4.8。
- 设置页：`15-fsrs-settings.png`（日常 90%）、`16-fsrs-94.png`（考前 94%），GLM-5.3-Flash PASS，开关与 chip 选中态清晰。
- 统计页：`67-fsrs-final2.png`，GLM-5.3-Flash PASS；FSRS-5 卡显示平均 R/S、今日到期、累计遗忘、下次间隔；热力图只有周四 16 点一格绿色，其余灰色空桶。
- 卡背：`82-cardback-scroll3.png`（appropriate 新 5 步 root 文案，含 /əˈprəʊpriət/ 与 /əˈprəʊprieɪt/），正文完整可滚动；滚动提示方案因遮挡正文已移除。
- 模拟器：`w3test`（720x1560，Android 14），`mf` 多次 SystemUI ANR/崩溃；截图均在 `D:\Figma\figma_codex\artifacts\wordhelper-v8-20261120\screens\`。

## 5. 未闭环 / 明早拍板

- 金丝雀不是 100%：唯一失败 `obscure`，原因是 sound 谐音「俺不熟」无法覆盖 /skjʊr/ 的 /k/、/j/ 滑音与卷舌 /r/，Judge 判 `phonetic_drift_unwarned=true`；这是生成策略问题，不是构建/接口问题。若要强行 20/20，可让 Judge 对 sound 谐音放宽，或在生成阶段强制该词走 root/shape；两者都会改变评测口径，等你拍板。
- `Migration23To24Test` 已单跑通过，但 `MigrationTest.kt` 旧链只到 12→13，全量 androidTest 仍会因缺 13→24 失败（既有问题，本次未改）。
- 分支未 push；未向 main 提交/强推。`canary_20.py`、结果 JSON、报告均在分支待提交（本报告生成后会一并提交）。
- 未做真实设备（非模拟器）安装；截图来自 w3test Android 14 模拟器。

## 6. 复现命令

```powershell
cd "D:\deepseek harness\work_anki_enligish\app\src_repo"
.\gradlew.bat clean testDebugUnitTest assembleDebug
cd "..\..\tools\rewrite-service"
$env:PYTHONIOENCODING="utf-8"
python -m unittest discover -s tests -p "test_*.py"
$env:CANARY_MODEL="ds-v41-flash"
python tests/canary_20.py --tag dsv41final
```



