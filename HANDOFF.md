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
| 换机恢复 | 清数据 → 填地址 → 重启，4 条历史结果按 seq 写回，rev 递增 |

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
