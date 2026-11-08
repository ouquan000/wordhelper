# 交接文档 · 不爱背单词（1.4.3.2 定版）

> 生成：2026-11-08 01:10（北京时间）
> 基线：`1bf5b57`（1.4.3.2 代码与文档）+ 本文件的文档提交
> 手机上的版本：**1.4.3.2（versionCode 8）**，真机 `10AF941PZJ00AKH` 已装并验证
> 上一版交接：`git show v1.4.2:HANDOFF.md`（1.4.2 定版时写的，端口/构建/踩坑部分仍有效）

---

## 0. 从哪开始（下个会话先看这三行）

1. **下一版是 1.4.4**，方向见 §9；用户还没拍板做哪几条，**先问再做**。
2. 当前工作区应干净（`git status`），HEAD 是本次文档提交，tag `v1.4.3.2` 指向定版。
3. 用户红线没变：**没被叫不许改代码**；真机默认不碰，动屏幕前说一声；C 盘不堆东西。

---

## 1. 一句话现状

1.4.3 系列（「重生」）已经交付到手机：卡背底部多了一条「重写思路」，点一下把问题标记发到电脑，
电脑用 `生成 → 润色 → 独立评分` 流水线重做这个词的**记忆思路**，评分不过就不写回；
手机下次回前台/翻面时自动换文，学习记录不受影响，可撤销一次。
1.4.3.2 是把这条链路的**手感**修好：条竖排不压字、随卡背一起滚、进度是真的、完成即换文。

### 本版（1.4.3.2）更新了什么

- **重写条重排**：按钮 / 模型下拉 / 输入框改成竖排三行（`fillMaxWidth`），不再挤成一行压字；
  输入框常显，提示「哪里有问题？留空＝整段重写」。
- **条随卡背滚动**：`CardBackBlocks` 新增 `backActions` 插槽，渲染在卡背内容末尾，
  跟着 `verticalScroll` 一起滚，不再贴死在卡片底部。
- **一次性清理学习记录**：升级后首次启动清空 `tb_progress`、`tb_early_review_ref`、`tb_new_word_ref`、
  `tb_daily_stats`、`tb_study_log`、`word_ml_stats`、`training_samples`，并把「今日新词计划」清零，
  用户今晚可以从头背。只在本次升级触发一次（DataStore 标记 `cleanup_132_done`）。
- **20 词测试书**：内置 `assets/test20_cards.json`，从六级卡等距抽 20 词，
  自动建一本「测试·20词」（不激活、可删），用来小步验功能。
- **生词本清空**：清理包含生词本引用（`tb_new_word_ref`），真机验证生词本 0 词。

### 1.4.3 / 1.4.3.1 也在这个包里（历史摘要）

- **1.4.3**：重写按钮 + 模型下拉（5 个模型）+ 离线标记队列 + 电脑端改写服务 + 失败不写回 + 撤销。
- **1.4.3.1**：重写条挪进卡背、真实阶段进度（排队/生成/润色/评分/完成）、翻面即同步、
  「完成即换文」（订阅 `tb_word_memory.approach` 的 Flow）、删除 EF 显示、电脑端「重写记录」页。

---

## 2. 版本与包

| 项 | 值 |
|---|---|
| `versionCode` / `versionName` | 8 / `1.4.3.2` |
| APK | `dist/english-app-1.4.3.2.apk`（33,139,773 B） |
| md5 | `e3bd6b57c4fb9a0016aac6ccb5be6183` |
| 下载（本机开 18795 时） | `http://10.105.85.173:18795/english-app-1.4.3.2.apk` |
| DB 版本 | **15**（14→15：`tb_word_memory` 加 `source/rev/prev_approach`，新增 `tb_rewrite_queue`） |
| 签名 | `_toolchain/signing/wordhelper.jks`，alias `wordhelper`，密码 `wordhelper123` |

**版本号铁律**：每次对外发版 `versionCode` 必须 +1；不为一个 bug 连跳版本号。
下个对外版本是 **1.4.4 → versionCode 9**；如果只改 schema 之类的小修，内部包沿用同号覆盖。

---

## 3. 架构：重写链路（1.4.3 新增的核心）

```
手机（Android）                             电脑（Python，tools/rewrite-service/）
─────────────────────────────────────      ────────────────────────────────────────
RewriteBar（卡背底部，ui/screen/RewriteBar.kt）
  ↓ 点「重写思路」
RewriteRepository.enqueueAndSync()          POST /rewrite/marks
  ↓ 本地 tb_rewrite_queue(status=pending)      ↓ 入队（store.py，stage=queued）
  ↓ 有地址就上传，失败留在队列
                                          claim() → stage=generate
                                            generate（prompts/generate.txt）
                                            polish（prompts/polish.txt）
                                            judge（prompts/judge.txt，独立 agent）
                                              两轮不过 → failed，不写回
  GET /rewrite/results?since=N  ←──────────  done，带 clientId / rev / approach
  ↓ applyResult（三层守卫：clientId ↔ wordId ↔ 当前标记）
  ↓ tb_word_memory(source=user, rev+1, prev_approach=旧文)
  ↓ 卡背通过 liveApproach Flow 自动换文
```

### 关键文件

| 文件 | 作用 |
|---|---|
| `app/.../data/entity/RewriteQueueItem.kt` | `tb_rewrite_queue`：wordId / word / instruction / model / status / error / createdAt / updatedAt |
| `app/.../data/dao/RewriteQueueDao.kt` | 队列读写、`getStaleQueued`、`observeOpenCount` |
| `app/.../data/dao/WordMemoryDao.kt` | 加 `observeApproach(wordId): Flow<String?>`、`getUserSourcedWordIds()`、`countAll()` |
| `app/.../data/repository/RewriteRepository.kt` | 核心：上传、拉结果、三层守卫、撤销、`liveApproach` 数据源、seq 回绕 |
| `app/.../data/repository/SettingsRepository.kt` | `cardBackUrl`、`rewriteLastSeq`、`rewriteModel`、`cleanup_132_done`、`test_deck_version` |
| `app/.../ui/screen/RewriteBar.kt` | 卡背底部的重写 UI（按钮/模型/输入/进度/状态/撤销） |
| `app/.../ui/screen/LearningScreen.kt` | `CardBackBlocks(backActions=…)`、`liveApproach` 接入、翻面回调 |
| `app/.../ui/viewmodel/LearningViewModel.kt` | `liveApproach`、进度轮询、`syncRewriteOnBackFlip`、`markRewrite` |
| `app/.../data/model/RewriteModel.kt` | 5 个模型的 id / 名字 / provider / reasoning |
| `app/.../data/model/CardSeed.kt` | 种子加载；新增 `loadTest()` 读 `test20_cards.json` |
| `tools/rewrite-service/` | 电脑端：`service.py`（HTTP）、`store.py`（队列/记录）、`pipeline.py`（生成/润色/评分）、`providers.py`（5 个模型 + grok 串行锁）、`prompts/`、`data/`（运行时，gitignore） |
| `tools/cardback-editor/` | 1.4.2 的卡背编辑器 + 同端口的重写路由（`/rewrite`、`/rewrite/records`、`/rewrite/panel`） |

### 电脑端接口（都在 8765）

```
GET  /cardback.json                       当前卡背清单
GET  /api/state  /api/history             编辑器状态/历史
POST /api/publish  /api/rollback          发布/回退卡背清单
GET  /models                              5 个模型定义
POST /rewrite/marks                       接收手机标记（花模型钱，别暴露公网）
GET  /rewrite/results?since=&limit=       拉结果（手机端增量）
GET  /rewrite/status                      队列状态（含每个词的 stage）
POST /rewrite/retry                       {"word":"…","clientId":"w6-…"} 精确重试
GET  /rewrite  /rewrite/records           人看的记录页 / JSON 分页
```

### 模型（`RewriteModels.DEFAULT`）

| id | 名字 | 备注 |
|---|---|---|
| `ds-v41-flash` | DeepSeek V4.1 Flash | 默认，最快（约 90–120s/词） |
| `step5` | Step 5 | 推理模型，`max_tokens` 必须 ≥12000，否则空 content |
| `glm53` | GLM 5.3 | 有时第一轮判不过、第二轮过 |
| `portdan-gpt` | Portdan GPT | 评分很严，常以「拆分不成立」判不过（属质量判罚） |
| `portdan-grok` | Portdan Grok | 中转站对并发敏感，已加进程内串行锁；一条 5–10 分钟 |

- 生成→润色用同一个模型；**评分用另一个模型**（不能又当选手又当裁判）。
- WB 网关（8788）忽略 `system` 角色，提示词必须放 `user`。

---

## 4. 构建 / 装 / 测

```bash
cd "D:/deepseek harness/work_anki_enligish/app/src_repo"
export JAVA_HOME="/c/Users/ouquan/AppData/Local/Programs/Microsoft/jdk-17.0.10.7-hotspot"
export GRADLE_USER_HOME="D:/deepseek harness/work_anki_enligish/_toolchain/gradle-home"
"$GRADLE_USER_HOME/../gradle-8.9/bin/gradle" --offline --no-daemon -q :app:assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

- **必须 `export` 两个变量 + `--offline`**：用 `VAR=… && gradle` 这种写法变量不会传给 gradle，
  会报 `Plugin [id: 'org.jetbrains.kotlin.android', version: '2.0.0'] was not found`。
- `local.properties` 用正斜杠：`sdk.dir=D:/Android/Sdk`。
- adb 全路径：`D:/Android/Sdk/platform-tools/adb.exe`（PATH 里没有）。
- 模拟器：AVD `mf`，`D:/Android/Sdk/emulator/emulator.exe -avd mf -no-window -no-snapshot -no-audio -gpu swiftshader_indirect`；
  已经在跑时再起会报 `Running multiple emulators with the same AVD`，直接用现成的 `emulator-5554`。
- 真机：`10AF941PZJ00AKH`，无线 `adb connect 10.103.216.69:5555`（USB 与无线可同时列出）。
- 拉起 App：`adb shell am force-stop com.kaoyan.wordhelper && adb shell monkey -p com.kaoyan.wordhelper 1`。
- 拉库：`adb exec-out run-as com.kaoyan.wordhelper cat databases/kaoyan_words.db > x.db`，
  **必须同时拉 `-wal`**（`x.db-wal`，放同目录），否则读到旧快照；拉到的目录要 Windows 可见（`.tmpdb/`）。
- 电脑端服务：`cd "D:/deepseek harness/work_anki_enligish" && python tools/cardback-editor/server.py`
  （0.0.0.0:8765；重写路由也在同一进程）。

### 模拟器坐标（vivo/API34，1080×2400，会因键盘 pan 漂移）

- 底部导航 y≈2208：学习 x=126 / 查词 501 / 词库 677 / 我的 953
- 卡背重写条：按钮 `[110,1436][970,1546]`、模型 `[110,1585][970,1647]`（1.4.3.2 竖排）
- 撤销按钮 `(901,1614)`；「知道了」引导 `(815,1429)`
- ANR 弹窗 Wait 按钮 `[71,1276][1009,1408]`（中心 540,1342）

---

## 5. 验证证据（都在 `docs/`）

| 文档 | 结论 |
|---|---|
| `docs/test-report-1.4.3.md` | 1.4.3 验收 7 PASS / 1 FAIL（FAIL 是五模型并发，后修） |
| `docs/review-1.4.3-round2.md` / `round3.md` | 复评 82.5 → 86.5，修完 P0 后「能装机灰度」 |
| `docs/test-report-1.4.3.1.md` | 1.4.3.1 5 PASS / 0 FAIL / 4 未验证（模拟器被关） |
| `docs/review-1.4.3.1.md` | 1.4.3.1 评分 **88.0/100**，无阻断 |
| `docs/spec-1.4.3.md` / `spec-1.4.3.1.md` / `spec-1.4.3.2.md` | 三版规格与已知限制 |
| 真机 1.4.3.2 | 启动无 FATAL；`tb_progress/daily_stats/new_word_ref` 全 0；生词本 0 词；「测试·20词」20 词未激活 |
| DB 迁移 | 1.4.2 真旧库（user_version=14）覆盖装 1.4.3 后 = 15，三列与队列表就位，无崩溃 |
| 换机恢复 | 1.4.4 及以前：清数据 → 填地址 → 重启，4 条历史结果按 seq 写回，rev 递增。**1.4.5 起这条能力失效**：`applyResult` 只认「本地还有这条标记」的结果，换机/清队列后历史重写结果一律丢弃（防穿透 P0 的代价）；学习进度仍由 `tb_progress` 同步链恢复，不受影响 |

---

## 6. 已知限制（别当 bug 报）

1. **grok 一条 5–10 分钟**，且中转站对并发敏感（已串行锁）；gpt 评分很严，常判不过。
2. **8765 监听 0.0.0.0 且无鉴权**：同网任何人可读全部结果、替任意词提交标记（花你的模型钱）。
   别暴露公网；要长期用建议加 token 或改回环。
3. **重写只改「记忆思路」**（`derivation`→`approach`），不改例句/搭配/释义。
4. **撤销只能回一次**（用 `prev_approach`），多设备不同步。
5. **1.4.3.2 清空学习记录只发生一次**（`cleanup_132_done`）；之后再装不会清。
6. 「测试·20词」不会自动切为当前书，需要手动切；删书只清孤儿词（`tb_word.word_key` 是全局唯一）。
7. 模型列表里没有「显示倍率」之类的 UI 设置，只有这 5 个。

---

## 7. 踩过的坑（新增，接着上一版继续）

1. **读库必须带 `-wal`**：只读 `.db` 会得到旧快照，会误判「没写回」。这一条让我白查了 5 小时。
2. **Python `http.server` 默认 HTTP/1.0**：回完即断，经 `10.0.2.2` 偶发响应尾部截断
   （客户端报 `ProtocolException: unexpected end of stream`，服务端日志是 200）。
   修法：服务端 `protocol_version = "HTTP/1.1"` + 客户端长连接 + 失败重试 3 次。
3. **端口 8790 是用户的 WB 网关主池**，8788 是 router，都别当测试 stub；测试用 18791/18795。
   杀进程前必须核对 `ExecutablePath` + `CommandLine`，不能只凭端口。
4. **Kotlin 块注释可嵌套**：KDoc 里写 `/rewrite/*` 会把后面的代码吞掉，报 `Unclosed comment`。
5. **`Int` 存服务端 `Long` seq** 会截断；要用 `longPreferencesKey`。
6. **`AnimatedContent` 的 `state.word` 是快照**：同词换文不会重组；`key(word.approach)` 也无效
   （key 是旧值）。正解是订阅 `tb_word_memory.approach` 的 Flow（`liveApproach`）。
7. **`uiautomator dump` 会读到上一次的文件**：dump 失败时 `/sdcard/ui.xml` 还是旧的，
   会误判「新 UI 没生效」。先 `rm` 再 dump，并看时间戳。
8. **MSYS 会改写 `/sdcard`**：用 `//sdcard/...` 或 `export MSYS2_ARG_CONV_EXCL='*'`。
9. **python 读 MSYS 的 `/tmp` 文件可能是空的**：把库拉到 Windows 可见目录（如 `.tmpdb/`）。
10. **`edit` 的 `old_string` 容易连吃相邻行**：改完立刻 `grep`/读回确认，别只看 exit 0。
11. **ADB 在 PATH 里没有**；`adb devices` 为空时先确认是模拟器没起还是 adb server 挂了。
12. **多开模拟器同 AVD** 会直接失败，别以为启动命令没生效。

（1.4.2 及更早的坑：Room 查询列要齐、`rootProject.file` 基准、签名路径、
C 盘、`syncPresetWords` 早退、`Card(onClick)` 8dp touchSlop、`contentKey`——
见 `git show v1.4.2:HANDOFF.md` §5，仍然有效。）

---

## 8. 内容资产（不在本仓库）

| 资产 | 路径 | 数量 |
|---|---|---|
| 词条文本（32 字段） | `D:\deepseek harness\workcodex\.tmp\export\by-word\<word>.json` | 5249 份 |
| A 图（832×1216） | `…\.tmp\img-a\<word>\assets\viewa\<word>-viewa.png` | 4859 张 |
| 权威词表 | `work——anki\pipeline\known_vocabulary_base\source\cet_full_list.json` | 5272 词 |
| 生成提示词（六脉神剑） | `D:\codex\MindForge_MF\english_production\word_pipeline\prompts\mnemonic-reference.txt` | 67 行 |
| 润色三份（A/B opus、C grok） | `D:\deepseek harness\workcodex\.tmp\reply-A-opus.txt`、`reply-B-opus.txt`、`reply-C-grok.md` | 已并入 `polish_v3.py` |

---

## 9. 下个版本（1.4.4）做什么

用户还没选，下面是按「对他实际有用 + 依赖已经具备」排的：

**P0（最该做）**

1. **批量重写**：现在只能一个词一个词点。加「按书/按筛选批量标记」入口，电脑端队列已能扛
   （`/rewrite/marks` 支持多条，服务端有 stage/重试）。这是用户最初就提的诉求——5500 词里
   思路差的会很多，一个个点不现实。
2. **重写记录页完善**：`/rewrite/records` 补 `instruction`、按书筛选、批量重试、失败原因可复制；
   手机端加一个「待处理/已完成」入口（现在只能去电脑看）。
3. **卡背动效接管**：schema 里 `motion` 参数（820/90/220/72%/-1/1.0015）已经在了，App 没读。

**P1（结构债，早做早轻松）**

4. **词书管理**：隐藏/删除两本内置大词库（`WordbookFullLoader` 每次启动重导，删书别删 assets）、
   生词本的隐藏开关（现在被星标/右滑/查词三处引用）。
5. **学习记录按书隔离**：`tb_daily_stats` / `tb_study_log` 没有 book 维度，想「只清测试书」
   做不到；加 `book_id` 需要 DB 16。
6. **换机/多设备恢复**：`clientId` 契约已经稳，但需要服务端 token、时间戳对齐、撤销同步。

**P2（实验，别动主进度）**

7. **调度算法对照**：现有算法 vs FSRS（Anki 4 已用 FSRS），只在「测试·20词」上跑影子模式，
   不动真实进度。参考：[FSRS 教程](https://github.com/open-spaced-repetition/fsrs4anki)、
   [srs-benchmark](https://github.com/open-spaced-repetition/srs-benchmark)、[Anki 手册](https://docs.ankiweb.net/deck-options.html)。
8. **卡背配图**：5183 词有 A 图，但 App 目前不需要图；要做就得想清楚「图从哪来、多大、怎么缓存」。
9. **安全收口**：8765 加 token / 只绑回环，把 `/rewrite/marks` 的调用成本挡住。

**用户点名的方向：个性化生成（2026-11-08 01:29，尚未开工）**

原话：「不要给我看 这个你能不能就是自己优化…就是你能不能基于数据来优化我以后生成文本的想法…我想做的更定制化 而且生成的内容更聪明」。

做法（只动电脑端，手机端不改 UI）：
1. **喂数据**：每张卡的 `approach` + 用户三键评分 + 复习间隔/遗忘 + 标记重写时的抱怨原文 + 重写后「保留还是撤销」。
2. **学画像**：把「记不住」当标签，回归到写法特征——词根 vs 谐音、段数、字数、有没有中文场景、是否硬编情节；
   产出「个人偏好档」；同时积累负样本（被否决的旧思路 + 用户原话）和正样本（被保留的新思路）。
3. **只改生成/润色**：偏好档作为软约束写进 `prompts/generate.txt` / `polish.txt`，负/正样本做 few-shot；
   **评分链路不碰**（独立评审，不能自己判自己）。
4. **验证**：影子 A/B，一半词用旧提示词；看「撤销率 / 同词再重写率 / 模糊+不认识占比」是否下降，再决定是否默认启用。

**明确不做**：把生成改回「整卡 22 字段」（用户只要记忆思路）；往 anki 回灌（用户明确说不用）。

---

## 10. 用户偏好与红线（1.4.3.2 更新）

- **结论优先**，四点式汇报（在做什么 / 已完成 / 接下来 / 卡点）。
- **能自动就自动**；只在关键决策找他（选方案、动数据、发版、装真机）。
- **前台上网/打游戏时**：不弹窗、不抢焦点、不重启他正在用的服务。
- **不要擅自开始写代码**，等一句「开始」。
- **测试要轻**：小改动小小测；不要每次都跑「长测试 + 独立评分」两轮。
- 交付只要：结论 + 报告 + 链接 + 待决事项；不贴截图。
- 不为一个 bug 连跳版本号；真机装包前说一声；C 盘不堆东西。
- 他今晚要开始背单词，**速度 > 完美**。

---

## 11. 新会话怎么开局

1. `git log --oneline -5`、`git status`、`git tag --list 'v1.4*'` 确认位置。
2. 读本文件 §0、§9；**先问用户这版做哪几条**，不要自己开工。
3. 要动重写链路：先起电脑端 `python tools/cardback-editor/server.py`，确认 `curl :8765/models` 有 5 个模型。
4. 真机/模拟器操作见 §4；拉库记得带 `-wal`。
5. 验完的产物放 `dist/`，文档更新 `docs/spec-x.y.z.md`，最后 `git commit` + tag。

---

## 12. 1.4.4 内部包（2026-11-08，versionCode 8 / 1.4.3.2 同号覆盖）

> 内部验证包不升号；对外发布时才 `versionCode 9 / 1.4.4`。

### 12.1 这一版做了什么（按用户原话）

1. **卡背方法标签**（用户：「六脉神剑有六个背书方法，能不能在卡背音标同一行加上像『联想记忆法』这样的 tag」）
   - DB 16：`tb_word_memory` 加 `method TEXT NOT NULL DEFAULT ''`。
   - `assets/word_method_map.json`：由 `by-word` 语料回填 5228 个种子词的方法（只有主招；语料里 `elements` 是纯字符串，没有逐元素方法，所以「主招 · 辅招」暂时只能显示主招）。
   - 一次性回填：`WordMethodMapLoader` + `WordRepository.backfillWordMethods()`，`SettingsRepository.KEY_METHOD_BACKFILL_DONE` 标记，重装/升级都只跑一次。
   - 卡背音标行右侧显示中文标签：谐音 / 联想 / 字形 / 词根 / 对比 / 多义（`LearningScreen.methodLabelOf`）。
   - 电脑端重写结果带回 `method`，`RewriteRepository` 应用时更新标签；`LearningViewModel.syncRewriteNow` 翻面/回前台时同时刷思路与标签。
   - **未做**：按 tag 的统计视图（用户提过「统计就能按 tag 统计」）——字段已就位，统计页还没接。

2. **学习统计双视图**（用户：「保留全局视图，再加每一本的独立视图，右上角加按钮切换，与原风格一致」）
   - 学习统计卡片右上角 `FilterChip`：全局 / 当前词书。
   - 全局沿用原指标（次）；按书视图 5 个指标：累计已学、已掌握、待复习、正确率、今日接触词数；底部注明「与全局的次不是同一口径，两者不能相加」。
   - 两种口径下都多一行「按记忆方法（已学 N 词）：联想 1200 · 词根 800 …」——`ProgressDao.getGlobalMethodCounts / getBookMethodCounts` 走 `LEFT JOIN tb_word_memory` 按 `method` 分组、`COUNT(DISTINCT word_id)` 去重（全局不重复算同一个词）。
   - 口径限制：按书「待复习」是进入页面时的快照，不会自己跳动；按书今日新词/复习/拼写拆分算不出来（`tb_daily_stats` / `tb_study_log` 没有 `book_id`）。

3. **隐藏两本内置大词库**（用户：「四本词库留最新的六级和 20 词测试还有生词本」）
   - 设置 → 内置大词库：隐藏（默认）/ 显示，`SettingsRepository.showBuiltinFullBooks`。
   - 隐藏时启动不再 `loadPresets/ensurePresetBooks`（省掉每次启动重导 5000+ 词）；如果当前在用的书恰好是被藏的两本，自动切到「六级记忆卡」。
   - 词库页/学习页的列表都用 `List<Book>.withoutBuiltinFull(hide)` 过滤；**没有删数据**，随时能开回来。

4. **学习记录同步（抄墨墨最小骨架，中间站放电脑）**
   - 电脑端：`tools/cardback-editor/sync_store.py`（JSON 落盘、原子写、坏文件转 `.broken`），`server.py` 加 `GET /sync/pull?since_rev=N`、`POST /sync/push`。
   - 手机端：`SyncRepository`，`tb_sync_op` 存待发 op、`tb_sync_state` 存 device_id / server_rev / last_sync_at。
   - 打点：`upsertProgress` / `applyStudyResult` / `applySpellingOutcome` / `markWordAsTooEasy` 写库后入队；一个词在几本书都有进度时**一本书一条 op**（op_id 带 bookId，避免互相覆盖）。
   - 冲突：每词 LWW，比 `last_review_time`（相等比 op_id），不弹窗；服务端幂等命中不涨 rev，手机队列才清得掉。
   - 分页：`pull()` 回 `next_since_rev`（还有下一页时=本页最后一条的 rev，拉完才=全局 rev），手机端按它推游标；拉满 20 页仍没完返回失败而不是「已同步」（验收 P0 修复）。
   - 入口：我的 → 数据管理 → 「同步学习记录（N）」；只点才联网，平时纯本地。
   - 不抄：云端排程、意图型 op、CheckpointSnapshot 换表、手动整包上传、墓碑删除。

5. **电脑端口令收口 + 记录页完善**（此前已定）
   - `tools/cardback-editor/.cardback_token`（首次启动生成，已 gitignore）；请求头 `X-Cardback-Token`；本机 127.0.0.1 免票；POST 校验 Origin；写请求限流 20/分、200/天。
   - 手机端设置里填「连接口令」，401 统一提示「口令不对，请核对电脑端启动窗口里「手机口令」那一串」。
   - 记录页：按书筛选、指令列、本页失败全选、批量重试（串行隔 400ms）、复制错误。

6. **提示词放宽 + 口吻**（用户：「字数都可以多一点，我感觉都像是为了三段妥协了很多」「需要时大胆一些」「说人话」）
   - `generate.txt` / `polish.txt` / `judge.txt` 重写：3–5 步、每行 40–50 字；`normalize()` 与 judge 同步放宽。
   - polish 叠回 8 种口吻、梗密度上限、明确允许冷梗/地狱笑话/双关（不改音不改义）、禁「该词表示 / 用于描述」等教材腔、8 条自检。
   - judge 第 6 维「像人说话」；`PASS_LINE` 仍 8。

### 12.2 真实验证记录（2026-11-08）

- 真实模型冒烟（`ds-v41-flash`）：`obscure` 5 步、6 维 8/9/10/8/8/9、177.7s；`brittle` 4 步、9/9/10/9/10/9、149.4s，均一次过。
- 服务端合约：push 幂等、旧 lrt 不涨 rev、pull 每词只留最新、缺字段 rejected、坏参数 400。
- 设备（模拟器 emulator-5554，411×892dp）：方法标签显示、统计切页、词库只剩两本、同步拉回 1 条（abandon 立即变「已到期」、进度 1/21）、评分后按钮出现「（1）」、推送后电脑端 rev 3→4、按钮清零。
- 已知环境问题：AVD 在 360×640dp 的极小屏下卡片会被压成一条缝（老问题）；模拟器冷启动要 40–60 秒。

### 12.3 还没做 / 下一步

- 按 tag 统计已做「已学词的方法分布」；还没有「按方法看正确率/遗忘率」这种效果对比。
- 重写结果页批量操作只到「本页」；失败原因复制在局域网 http 下走 `execCommand` 兜底。
- 按书统计若要「今日新词/复习/拼写」这种拆分，得给 `tb_daily_stats` / `tb_study_log` 加 `book_id`（DB17）。
- 换机同步目前只搬学习进度，不搬卡背改写版本（`seq`/撤销仍在电脑端）。
- 卡背动效：`docs/motion-ref/` 有三个参考 demo，App 未接。
- 按书视图只能看「当前词书」（没有书目选择器）；要看别的一本得先切当前词书。
- 拉取时词/书不在本机的行会跳过且不回补（两本大词库默认隐藏时尤其明显）。
- 「显示内置大词库」需下次启动才导入，文案已改成「开回来下次启动生效」。
- DB16 还没有迁移测试用例（`MigrationTest` 只覆盖到 12→13）。

### 12.4 验收修复记录（2026-11-08 15:2x）

- **P0 同步分页丢行**：服务端 `pull()` 增 `next_since_rev`（`sync_store.py`），手机端 `PullPage.nextSinceRev` 优先用它推游标；`MAX_PULL_PAGES` 用尽改为失败提示。600 条合约复测：500 + 100，无丢行。
- 统计卡新增按记忆方法分布（全局/按书都跟口径切）。
- 「内置大词库」说明文案改为「开回来下次启动生效」。
- 复核：`compileDebugKotlin` 零错误；`assembleDebug` 重新出包。

### 12.5 二轮复审补修（2026-11-08 15:4x–15:5x）

- **首次同步全量补历史**（复审指出的真空：升级前背的词没有 op 记录，永远到不了电脑）：`SyncDao.allLearnedProgress()` 只读捞 `status>=1` 的进度，`SyncRepository.bulkEnqueueIfFresh()` 在「服务端 rev=0 且从没同步过」时按 500 一批补进 `tb_sync_op`（op_id 相同，重跑不会重复）。
  - 端上实测：清空队列、只留 1 条历史进度 → 点同步 → 电脑 `sync.json` rev 0→1、落 1 行（appropriate）；再点一次显示「已经是最新的了」、rev 不变。
- **拒收不再卡队列**：`PushResponse` 增 `rejected`，服务端明确不收的 op（缺字段）直接删并留日志，避免「待同步 N」永远不清零。
- **pull 不再带 `device_id`**：服务端根本不读它，注释与实现对齐。
- **按方法统计口径**：分子分母都只算有标签的词，标题改「按记忆方法（已标注 N 词）」，另起一行「另有 M 词还没有标签（重写思路后会补上）」。

---

## 13. 1.4.5：翻面手感、整行口令、按书记忆与按书曲线（2026-11-08，versionCode 10）

### 13.1 这版改了什么

1. **卡背翻面不再卡一下**：两面常驻，换词后停 400ms 再挂背面，不可见那一面不接收点击和滚动；旋转角改成 `derivedStateOf` 只读值，避免每帧重组。
2. **电脑端地址与口令合成一行**：启动窗口 / 编辑器右下角复制出来是 `http://IP:8765#<口令>`，手机端「卡背结构地址」整行粘进去即可；单独的口令输入框已删。老用户只填过地址的照旧可用（token 仍单独保存）。
3. **重写结果按书存**：新表 `tb_book_memory(word_id, book_id, …)`。卡背先读本书的版本，没有才回落到全局；重写、撤销、方法标签、翻面后即时刷新全部按书走。测试·20 词里改思路不再影响六级记忆卡。
4. **按书每日学习量**：新表 `tb_book_daily_stats(date, book_id, new_count, review_count, spell_count)`，与全局表并行写；统计卡切「当前词书」后出现「过去一周趋势（这本书）」，Y 轴按整数刻度。**按书记账从升级当天开始，更早的历史没有按书拆分。**
5. **自动备份**：回到前台时静默同步一次（5 分钟节流），电脑没开/没填地址就快速跳过；数据管理里手动按钮与回执保留。

### 13.2 数据与兼容

- DB 16 → 17；`MIGRATION_16_17` 建两张新表 + `tb_rewrite_queue.book_id`（默认 0 = 老队列，写全局）。
- 15 → 17 完整链路在真机备份上跑过：`user_version=17`、85 条进度 / 5 本书 / 5228 条记忆 / 21006 个词 / 31774 条词书内容一条不少，`integrity_check=ok`，`foreign_key_check` 为空。
- 老的重写结果仍在 `tb_word_memory`（全局），没有本书版本时作为回落，不会丢。

### 13.3 验证记录

- `compileDebugKotlin` 零错误；`assembleDebug` 出包（vc10 / 1.4.5）。
- 模拟器 emulator-5554：vc10 覆盖安装首启正常；卡背翻面 + 「词根」胶囊 + 重写三行条可滚到；统计切「当前词书」显示按书 5 项 + 空态「从今天起按书记账，明天就能看到曲线」；答一张卡后 `tb_book_daily_stats` 落 `2026-11-08|book 1|new 1|0|0`，趋势图出线且 Y 轴为 0/1 整数；设置里只剩「卡背结构地址」一个框（占位文案「粘贴电脑上那一行」）。
- 自动同步：答完一张卡 → 回到前台 → 电脑端 `sync.json` rev 0→1，行内 `op_id=p-2-1-<lrt>`、`b=六级记忆卡`。

### 13.4 已知限制

- 翻面「顺不顺」只能真机手感判断，模拟器看不出。
- 按书曲线只有升级之后的数据；按书连续天数仍是全局口径（不低估，但也不是本书的）。
- 自动同步只在同 WiFi、电脑端服务开着时真传；否则静默跳过，不报错。
- 电脑端要重启一次 `server.py` 才会打印新的「手机：粘贴这一行」，旧窗口里还是旧格式。
- 书目选择器仍未做：按书视图看的是「当前词书」，要看别的一本先切书。

### 13.5 测试鱼结论与收尾修复（同日）

测试子代理在模拟器上 6 项全 PASS（翻面 / 统计双视图 / 按书隔离 / 整行口令 / 自动同步 / 稳定性），报告 `_recon/test-report-1.4.5.md`，证据 47 件在 `_recon/test-shots-145/`。按书隔离在数据层确认：`tb_book_memory` 只有 `(131, 2)`，`tb_word_memory` 同词仍是旧文；撤销在干净用例上 rev 1→0 且 10 秒不回弹。它另外提了三件「非阻断但该修」：

1. **卡背方法标签不随重写刷新**（evaporate 实测）。根因：卡背读的是分页快照里的词对象。修法：新增 `observeMethodForBook` / `observeMethod` 两条 DAO 查询 + `RewriteRepository.observeMethod()` + `LearningViewModel.liveMethod`，卡背 `WordCard` 同时用 `liveApproach` 与 `liveMethod` 覆盖快照。实测重写后「词根」胶囊即时在。
2. **「关于软件」显示 1.4.4**。根因不是配置：增量编译把 `BuildConfig.VERSION_NAME` 常量内联成了旧值（生成的 BuildConfig 是 1.4.5，dex 里却留了「版本 1.4.4」）。修法：`AboutCard` 改为运行时读 `PackageManager.getPackageInfo().versionName`；装机确认显示「版本 1.4.5」。
3. **重写跑到 100% 进度条不收、把撤销挤出屏幕**。修法：`RewriteBar` 在 `DONE` 后 1.5 秒收起进度条（`keepProgress`）。

顺手还修了：`RewriteRepository.upload()` 的书名取 `item.bookId`（原来取 `word.bookId`，测试书里重写会被电脑端记成「六级记忆卡」），旧思路也改取本书那份；连不上时的提示补上「电脑换过网络地址会变，重新粘那一行」；`RewriteBar` 离线态文案同步。重打包 `dist/english-app-1.4.5.apk`，34,132,820 B，md5 `3f924fc272b4261a3e5dcc3ec8fa6ef4`。

### 13.6 评审鱼 P0 与修复（同日）

评审报告 `_recon/acceptance-1.4.5.md`（162 行，截图 `_recon/review-shots-145/`）：五条里四条 PASS，**按书隔离 FAIL（P0）**。场景：撤销后切回学习页再同步，一条迟到（或重投递）的结果仍被接受；`bookId = mark?.bookId ?: 0L` 于是变成 0，把测试书的文本写进全局 `tb_word_memory`，六级书在没有本书记忆时回落全局，于是主书被穿透。

修法（`RewriteRepository.applyResult`）：**本地没有这条标记就直接丢弃结果**。撤销/已应用/换机清队列都算「没有标记」，宁可丢一条，也不再写全局；有标记时仍要求词号与标记完全一致。顺带修按书方法分布：`ProgressDao.getBookMethodCounts` 先读本书 `tb_book_memory.method`，没有再回落全局标签。

端上闭环（模拟器）：测试书重写 `system` 成功 → 本书 `(1436, 2)` 写入 `root/user/rev1`，全局仍 `seed/rev0`；点撤销回到旧文；清掉手机端设置造出「拉取游标归零」→ 翻面触发拉取，电脑端日志出现 `GET /rewrite/results?since=0` 又把历史结果重投了一遍 → 拉库确认全局仍是 `seed/rev0`、队列为空、本书回到撤销后的状态。**即：迟到的作废结果被丢弃，不再穿透。**

重打包：34,132,820 B，md5 `2e597411417d4f565cadb264b5acc6e8`（vc10 / 1.4.5 / DB17）。

评审鱼留的 P1/P2（**本轮不修，已知**）：撤销不回滚方法标签（没有 prev_method 列）；跨书共享 SRS（同一词在两本书复习进度共用，1.4.5 之前就这样）；16→17 没有自动化迁移测试（人工脚本已跑，见 §13.2）；重写实测 4m48s；400ms 预热可被 `|| isFlipped` 绕过；daily 增量并发可能丢 1 次；同步失败也会写 `lastAutoSyncAt`。

复审报告见 `_recon/rereview-1.4.5-p0.md`（84 行）：**P0 闭合**。反事实核对——服务端 seq29 结果的 clientId `w1436-1794139803756` 与作废队列行严格对应，旧代码下必写全局；同一份包重投后 `tb_word_memory` 零写入（max(updated_at) 停在 20:15:00.858、user 行仍 8），且 `rewrite_last_seq` 推进到 29，说明整页结果被逐条处理并丢弃。其余三处写全局（`undo`、种子导入、`backfillMethod`）都不由网络结果驱动。复审另提三个残留（均非本 P0）：老队列 `book_id=0` 升级瞬间会按全局落地（等价 1.4.4）；`cancel()` 对 QUEUED 不删行，取消后结果仍落本书；`undo` 不还原标签。
