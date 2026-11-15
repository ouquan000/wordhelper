# 交接文档 · 不爱背单词（1.4.6.2 已交付 / 1.4.7 准备中）

> 生成：2026-11-09（北京时间）
> 基线：`382e06b`（release commit，annotated tag `v1.4.6.2`）；交付时工作区干净；仓库无 remote，未 push
> 手机上的版本：**1.4.6.2（versionCode 13，DB20）**
> 上一版定版交接：`git show v1.4.3.2:HANDOFF.md`；1.4.6 的交接见本文件 §14

---

## 0. 从哪开始（下个会话先看这四行）

1. **当前是 1.4.6.2 已交付**：`382e06b` / annotated tag `v1.4.6.2` / APK 33,788,038 B / DB20；141 项单测全过。三版合成一个 tag（1.4.6、1.4.6.1 没有单独 tag）。
2. **下一版是 1.4.7「远程同步专版」**，只列了计划（§15.6），**没开工**；先等用户拍板做哪几条。
3. **远程同步卡在两道 gate**：`ouquansdiary.cn` 已买、实名已过，注册局恢复解析要 1–2 个工作日；cloudflared 未装、Cloudflare 未登录、NS 未改。**到登录 / 2FA / Authorize / 改 NS 就停手，等用户本人。**
4. 红线没变：没被叫不改代码；8765（PID 32740）是常驻服务不要随意重启；真机安装/发版必须用户点头；C 盘不堆东西。

---

## 1. 一句话现状

1.4.6 系列已经把「词包 v2 + 跨书 SRS 解耦 + 冷启动回插 + 卡背 UI 收拾」交付到 **1.4.6.2**（vc13 / DB20 / 141 tests）。
下一步 **1.4.7 是远程同步专版**：目标是固定 HTTPS 地址、断网/重启能自愈、出错能看懂；当前只有方案与 8765 鉴权修复，公网入口尚未打通。
1.4.3 系列的「重写链路」仍是主骨架，历史见 §3 与 §12–§14。

### 1.4.3.2 更新了什么（历史，2026-11-08）

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

> 本节是 **1.4.3.2 发版时的快照（历史）**。当前版本看 §15：`versionCode 13 / 1.4.6.2 / DB20`，APK `dist/english-app-1.4.6.2.apk`。

| 项 | 值 |
|---|---|
| `versionCode` / `versionName` | 8 / `1.4.3.2` |
| APK | `dist/english-app-1.4.3.2.apk`（33,139,773 B） |
| md5 | `e3bd6b57c4fb9a0016aac6ccb5be6183` |
| 下载（本机开 18795 时） | `http://10.105.85.173:18795/english-app-1.4.3.2.apk` |
| DB 版本 | **15**（14→15：`tb_word_memory` 加 `source/rev/prev_approach`，新增 `tb_rewrite_queue`） |
| 签名 | `_toolchain/signing/wordhelper.jks`，alias `wordhelper`，口令见本地 `app/src_repo/app/keystore.properties`（不入库） |

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

> 本节是 1.4.4 开工前的候选（历史）。1.4.4 / 1.4.5 / 1.4.6 已交付；**1.4.7 的计划见 §15.6**。

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

## 10. 用户偏好与红线（1.4.6.2 补充）

- **结论优先**，四点式汇报（在做什么 / 已完成 / 接下来 / 卡点）。
- **能自动就自动**；只在关键决策找他（选方案、动数据、发版、装真机）。
- **前台上网/打游戏时**：不弹窗、不抢焦点、不重启他正在用的服务。
- **不要擅自开始写代码**，等一句「开始」。
- **测试要轻**：小改动小小测；不要每次都跑「长测试 + 独立评分」两轮。
- 交付只要：结论 + 报告 + 链接 + 待决事项；不贴截图。
- 不为一个 bug 连跳版本号；真机装包前说一声；C 盘不堆东西。
- **多步配置纪律（2026-11-09 新增）**：账号登录、2FA、扫码授权（Authorize）、改 NS、云厂商实名/备案，做到这一步就停，交还用户本人；不代点「同意/授权」。
- **服务稳定优先**：8765、8011 这类正在给用户或手机用的进程，不确认就不重启/不 kill；改完 `server.py` 要重启也先说清楚影响。
- （1.4.3.2 当时）他今晚要开始背单词，**速度 > 完美**。

---

## 11. 新会话怎么开局

1. `git log --oneline -5`、`git status`、`git tag --list 'v1.4*'`：HEAD 应为 `382e06b`，tag 为 `v1.4.6.2`；目前只有 `v1.4.2`、`v1.4.3.2`、`v1.4.6.2` 三个 tag。
2. 读本文件 §0、§15；**先问用户 1.4.7 做哪几条**，不要自己开工（远程同步卡在域名 / Cloudflare gate，见 §15.4）。
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


## 14. 1.4.6 交接（2026-11-08 深夜）

### 14.1 现在的状态

- 代码：`main`，1.4.5 全部改动已提交（最新 `9e18d83` docs；功能提交 `4a12b4b`、`5869fe7`、`e9f0824`）。
- 工作树：干净。**半成品在 stash**：`wip: 自动找电脑（探路，未完成）`（新增 `CardBackDiscovery.kt` + 4 文件未完成改动，**没编译过**）。要接着做就 `git stash pop`；不打算做可以先不管。
- APK：`dist/english-app-1.4.5.apk`，34,132,820 B，md5 `2e597411417d4f565cadb264b5acc6e8`，vc10 / 1.4.5 / DB17。
- 真机：vivo V2408A（PD2408）已覆盖安装 vc10；无线调试 `10.103.216.69:5555`；原 USB 序列号 `10AF941PZJ00AKH`（开 tcpip 后会失效）。
- 数据：真机升级到 DB17，85 条进度 / 5228 条记忆 / 4 条 done 重写保留；3 条卡住的 pending 已在升级前清掉。
- 电脑端：`tools/cardback-editor/server.py` 常驻 8765；口令在 `tools/cardback-editor/.cardback_token`；手机里已存 `http://10.105.85.227:8765#<token>`。
  ⚠️ 电脑 WLAN 是 DHCP，今天 13:08 从 `10.105.85.173` 变成 `10.105.85.227`——这正是「重写突然用不了」的根因。

### 14.2 1.4.5 交付了什么（一句话一条）

1. **翻面不卡**：卡背两面常驻，换词后 400ms 再挂背面，不再在 90° 时首次组合整棵背面。
2. **口令整行粘**：电脑启动窗口打印 `http://ip:8765#token` 一行，手机整行贴进「卡背结构地址」即可。
3. **回前台自动同步**：5 分钟节流，静默把学习进度推到电脑；数据管理里仍可手动点。
4. **按书隔离**：新表 `tb_book_memory`，重写/撤销/读取都按「这本书」走；没有本书版本时回落全局。
5. **按书统计**：新表 `tb_book_daily_stats`，学习页可切「全局 / 当前词书」，按书出曲线（整数刻度）。

### 14.3 验证与证据

- 合约/迁移：`pull` 600 条按 500+100 两页取完、无丢行；真机备份库跑 15→16→17 后 `integrity_check=ok`、`foreign_key_check` 为空、条数全对。
- 测试鱼：`_recon/test-report-1.4.5.md`，6 项全 PASS，47 件证据在 `_recon/test-shots-145/`。
- 评审鱼：`_recon/acceptance-1.4.5.md` 指出 1 条 P0（撤销后迟到结果写全局），已修；复审 `_recon/rereview-1.4.5-p0.md` 判闭合。
- 真机：vc10、DB17、数据完整；关于页显示「版本 1.4.5」。

### 14.4 已知限制（已告知用户，先背着）

- 撤销只回滚正文，不回滚方法标签（缺 `prev_method`）。
- 同一个词在两本书共用一个复习进度（1.4.5 之前就如此）。
- 按书曲线只有升级之后的数据；按书视图只能看「当前词书」。
- 同步跳过行不回补：词/书不在本机时游标照走，之后补不回来。
- 重写实测 4–8 分钟；换机后重写结果不再自动写回（守卫只认本地标记）。
- 8765 口令是明文 HTTP，只防误连与蹭用，不防窃听。

### 14.5 1.4.6 候选（工程侧；用户的具体优化点待他口述后并入）

- **A 自动找电脑**：扫上次网段 + 扫本网段 + UDP 广播兜底；跨网段时广播不可依赖。半成品在 stash。
- **B 书目选择器**：按书视图直接选任意一本，不必先切当前书。
- **C 同步跳过行回补**：记录被跳过的 key，下次补拉。
- **D 撤销回滚标签**：`tb_book_memory` 加 `prev_method`（DB18，需迁移与备份库复测）。
- **E 跨书 SRS 解耦**：影响老数据语义，先出方案再动。
- **F 动效接管**：`docs/motion-ref/` 三个 demo，参数已量好，翻成 Compose spring。
- **G 个性化提示词 v1**：语料仍不足（6 条记录、instruction 全空），先攒真实抱怨再动。

### 14.6 红线与后台进程

- 不抢用户的手机/电脑：点屏幕、装包、改设置前先问；用户说「我来」就立刻停手。
- 用 adb 填表单别硬来：中文输入法会把 URL 变全角，`#` 还会被 shell 当注释截断。稳妥做法是 `ime disable` 两个中文输入法 → `input text '整行'` → 完事 `ime enable` 并设回默认。
- 8765 是常驻服务，别随便 kill；改了 `server.py` 要重启才生效（重启会重新打印手机那一行）。
- 模拟器 `emulator-5554` 可能还开着，用户说「要打游戏/省内存」就先关它和 gradle。
- `_backup/` 有真机数据库（含个人学习数据）：已 gitignore，不要提交、不要外传。
- 发版规则：内部验证包同 `versionCode` 覆盖；对外发版 `versionCode` +1（当时的下一版预估 →11；实际 1.4.6=11、1.4.6.1=12、1.4.6.2=13，见 §15）；DB 变更必须写迁移并在真机备份副本上复测。

---

## 15. 1.4.6.2 交付与 1.4.7 交接（2026-11-09）

### 15.1 状态：版本、包、测试

| 项 | 值 |
|---|---|
| release commit | `382e06b`；交付时工作区干净；仓库无 remote，**未 push** |
| tag | annotated **`v1.4.6.2`**（tag message 里记了 APK checksum） |
| `versionCode` / `versionName` | **13 / `1.4.6.2`** |
| DB | **20**（`app/src_repo/app/src/main/java/com/kaoyan/wordhelper/data/database/AppDatabase.kt:80`） |
| APK | `dist/english-app-1.4.6.2.apk`，**33,788,038 B** |
| md5 | `906aa1e71f079201fd1ef11e975b3ad2` |
| 单测 | **141 tests，0 failed / 0 errors**（`app/src_repo/app/build/test-results/testDebugUnitTest/`，23 个 XML 汇总） |
| 临时下载（可能过期） | `https://gofile.io/d/Na0LRieC` |
| 同 WiFi 下载 | `http://10.105.85.227:8011/english-app-1.4.6.2.apk`（8011 仍在跑） |

- **tag 覆盖三版**：1.4.6 / 1.4.6.1 没有单独 tag，`v1.4.6.2` 是三版合并的交付点。历史 tag 只有 `v1.4.2`、`v1.4.3.2`（再加本次 `v1.4.6.2`）。
- APK 不入库：`.gitignore` 的 `dist/*.apk` 命中；checksum 只写在 tag message 与本文。
- 三个包的 versionCode 已用 aapt2 核对：1.4.6 = 11、1.4.6.1 = 12、1.4.6.2 = 13。

### 15.2 三版做了什么（一句话一条）

**1.4.6（词包 v2 + 数据解耦，DB18）**

- 词包 v2：**5228 词**、**4850 条释义**更新；来源 ECDICT 免费词库（MIT）。`content/seed/cet6_cards.json` 的 `words` 长度 = 5228。
- 词根主方法 **951 → 0**，**2948 张**卡的 `approach` 被改写（报告 `_recon/word-audit/root-rewrite-report.md`）。
- DB18 跨书 SRS 解耦：老进度归当前书；`type=2` 生词本保留；同版加 `prev_method`、`tb_rewrite_skipped`、`tb_feedback`。
- 跳过等待 / 随机回插：点「跳过，先背下一个」出现等待卡；结果回来后 1–3 位内插回。
- 👍/👎：本地落 `tb_feedback`，电脑端可见；断网留待补发。
- 按书热力图；P0 种子重导保留用户标签（`source=user` 不被冲掉）。

**1.4.6.1（冷启动回插 + 补发，DB19/20）**

- 冷启动后跳过的词能插回：`tb_rewrite_queue.needs_reinsert` 把「待插回」意图持久化（DB20）。
- DB19：反馈绑到思路版本（`tb_feedback.approach_rev`），换过思路可以重新评价。
- 地址兜底 / 网络恢复后补发；普通卡也能反馈。
- 最终验收 50 项 100/100（`_recon/1.4.6.1-fix2-report.md`）。

**1.4.6.2（纯 UI）**

- 顶部「认词 / 拼写」可折叠。
- 点赞行移进卡背滚动区最底部，普通卡可见。
- **不改 DB、不改同步协议**；测试报告 `_recon/1.4.6-test-report.md`。

### 15.3 远程同步现状（1.4.7 的地基）

**服务**

- 8765 当前 **PID 32740**，监听 `0.0.0.0:8765`；交接时 `curl /rewrite/status` 健康。
- token 在 `tools/cardback-editor/.cardback_token`：**22 字符**（文件带 CRLF 共 24 B），sha256 前 8 位 `32656aac`。
- 手机端仍是 `http://<电脑IP>:8765#<token>`，**只支持同一局域网**；电脑 DHCP 换 IP 就要重填（§14.1 的旧账）。

**2026-11-09 修掉的致命鉴权洞**

- `tools/cardback-editor/server.py:863` 的 `_is_local()` 原来只看 TCP peer。Cloudflare 回源是 `127.0.0.1`，公网请求会被当成「本机」直接免 token。
- 现在：**`loopback peer` 且没有 `CF-Connecting-IP`** 才免票；其余一律要 token。
- 已按五组请求验过：本机 200、带 CF 头无 token 401、带 CF 头 + token 200、LAN 无 token 401、LAN + token 200。**这组是已验证的**，别改回去。

**旧进程事故（1.4.6.1 期间）**

- 8765 进程起于 **11-08 15:58**，而 `/rewrite/feedback` 路由 **11-09 01:14** 才写进 `server.py`；旧进程没有这条路由。
- 结果：App 打来 **186 次 404 + 22 次 429**（4 秒重试把限流吃满），反馈一直发不出去。
- 重启后 `/rewrite/feedback` 200，App 的 4 条 feedback 全部 `synced=1`。
- 教训：**改完 `server.py` 必须重启**；版本/能力要能自报，App 要能提示「电脑端版本过旧」，不能只靠 404 猜（1.4.7 P0）。

### 15.4 域名与隧道：卡在哪

- 域名 `ouquansdiary.cn` **已买**（腾讯云，38 元，订单处理中）；实名已通过；**注册局 1–2 个工作日恢复解析**。
- NS 仍是 `swallow/baseball.dnspod.net`，**没有改到 Cloudflare**。
- `anki.ouquansdiary.cn` 在本机解析到 `198.18.0.180`：这是代理的假 IP，**不是真实解析**，不能当成「域名已通」。
- `cloudflared` **未安装**；`~/.cloudflared` 只有旧 `config.yml`，**没有 `cert.pem`**；Cloudflare **未登录**。
- 现在能做的只有「装 cloudflared + 等用户登录 / 改 NS」；**到登录、2FA、Authorize、改 NS 就停手**，等用户本人。

### 15.5 方案：Cloudflare Tunnel（主）vs Tailscale Funnel（备）

| 方案 | 手机要装什么 | 成本 | 主要风险 | 结论 |
|---|---|---|---|---|
| **Cloudflare Tunnel + `anki.ouquansdiary.cn`** | 什么都不用装 | 域名 38 元 + CF 免费档 | `.cn` + 国内宽带公网 Web 可能被要求 ICP 备案 / 被墙；CF 登录与 NS 变更要用户本人 | **主方案**，先技术验证 |
| Tailscale Funnel | 电脑装 Tailscale，手机不需要 | 免费 | 国内连通性 / 带宽待实测；电脑需常驻 | 备选，不默认 |
| 换非 `.cn` 域名 | 什么都不用装 | 新域名年费 | 多一笔钱；仍要过 CF；旧域名闲置 | 最后手段 |

- **隧道本身不改 App**：现有 `base#token` 连接串在 HTTPS 域名下应直接可用；App 侧的连接串解析、状态页与重试是 1.4.7 的 P0，另列。
- 决策细节见 [`docs/decisions-1.4.7.md`](docs/decisions-1.4.7.md)。

### 15.6 1.4.7 计划（只列，不实施）

**主题**：远程同步专版——远程可达 + 同步可靠 + 可诊断 + 可切换。**不混** UI 八条、root 尾注、动效。

**P0（必须做）**

1. **公网入口**：cloudflared named tunnel + 开机自启 / 健康检查 / 自动重启。
2. **一次配置**：App 连接串 `https://host#token`；记住上次成功 + 候选地址；`/health` 版本 / 能力握手。
3. **补发与幂等**：网络恢复 / 回前台 / 冷启动补发；指数退避；跳行回补、游标、反馈 outbox 幂等。
4. **同步状态页**：已连接 / 重试中 / 失败原因 / 最后成功 / 待发条数 / 立即重试。
5. **旧进程提示**：电脑端自报版本与能力，App 明确提示「电脑端版本过旧」，不再静默 404。
6. **安全**：token、限流、CF 头、日志脱敏；token 不进日志、不进查询串。

**P1（有空再做）**

- PC 守护：cloudflared + 8765 存活、日志、异常通知。
- 二维码 / 复制串 / 诊断导出。
- runbook（电脑重启、隧道挂、token 换、域名过期怎么处理）。

**明确不做**

- 折叠理由框、大跳过、多张重写完成判定、热力图 chip 位置、查词入口、root 尾注、动效、多设备合并。

### 15.7 验收矩阵（1.4.7 交付时逐项过）

| # | 场景 | 验收标准 |
|---|---|---|
| 1 | 4G ⇄ WiFi 切换 | 切网后自动恢复，不重复提交、不丢队列 |
| 2 | 断网 > 重连 | 恢复后补发；没有 404/429 风暴 |
| 3 | App 杀进程 / 冷启动 | 跳过词仍能插回（`needs_reinsert`）；outbox 不重复 |
| 4 | 电脑重启 | cloudflared 与 8765 自启，`/health` 200 |
| 5 | 隧道重启 / 地址变化 | App 用「上次成功 + 候选」自愈，不必重填 |
| 6 | 旧 8765（无 feedback 路由） | 状态页明确「电脑端版本过旧」，不静默 404 |
| 7 | 错 token | 401 + 换 token 指引；不无限重试 |
| 8 | 429 | 按限流窗口退避，状态页显示等待时间 |
| 9 | 24h soak | 内存 / 日志 / 队列不增长，重试有上限 |
| 10 | 远程重写 / 跳过 / 反馈 / 回拉 | 四条链路在 HTTPS 域名下全部可用 |

> 以上全部**待做、待验证**；本文不预填「已验证」。

### 15.8 红线、后台进程、卫生

**红线**

- 没被叫不改代码；真机安装 / 发版必须用户点头；不动用户正在用的设置与服务。
- 8765（PID 32740）是日常服务，**不要随意重启**；改了 `server.py` 必须重启才生效，重启前先说明影响。
- 到 Cloudflare 登录 / 2FA / Authorize / 改 NS / 腾讯云实名或备案操作：**停手，等用户本人**。
- C 盘不堆东西；大文件都放 D 盘。

**后台进程（交接时）**

- 8011：`python -m http.server 8011`，PID 38976，job `bash-9c306a700388443a`，在发 1.4.6.2 APK。
- 8765：PID 32740（日常服务，见上）。
- 模拟器：headless `emulator-5554`，job `bash-b9ebb94446ad4f1b`；**可以关**。
- 真机 `10.103.216.69:5555`：**未触碰**。

**卫生（只报告，不动手）**

- 工作区体积：`_toolchain` 1.9G、`_recon` 1.1G、`dist` 397M、`app` 420M、`.tmp` 145M、`artifacts-test` 160M。
- 根目录还有 `m.xml` / `uz.xml`（测试 dump）、`cyber-eggs/`、`folia-music-player-mini/`、`word-breakout-visual-lab-mini/` 等非本项目目录。
- 建议：本项目测试 dump 移进 `_recon/`；其余等用户确认。**不要自行删除。**

### 15.9 下一步候选（要多久 / 风险 / 需要用户拍板什么）

1. **等解析 + 配 Cloudflare Tunnel**（推荐先做）
   - 要多久：gate 打开后 30–60 分钟（装 cloudflared、建 named tunnel、绑 `anki.ouquansdiary.cn`、设自启）。
   - 风险：`.cn` + 家宽公网 Web 可能被要求 ICP 备案或直接不通；CF 登录 / NS 变更必须用户本人操作。
   - 要拍板：用不用这个域名、同不同意把 NS 改到 Cloudflare、能否接受可能的备案流程。
2. **Tailscale Funnel 备选**
   - 要多久：30–45 分钟（只在电脑侧装）。
   - 风险：国内连通性 / 带宽待实测；电脑需常驻；服务可达性依赖 Tailscale。
   - 要拍板：是否接受电脑装 Tailscale、用它做公网入口；带宽够不够重写 / 同步。
3. **换非 `.cn` 域名**
   - 要多久：买域名 10 分钟 + 配置约 1 小时。
   - 风险：多一笔年费；仍要过 CF；旧域名闲置。
   - 要拍板：预算与域名。
4. **App 侧连接串 + 状态页 + outbox**（可与 1 并行）
   - 要多久：2–4 天（含测试鱼 / 评审鱼两道闸）。
   - 风险：动同步状态机，容易过度设计；要回归 141 tests 与真机。
   - 要拍板：本版做不做、先做哪几项。
5. **PC 守护 + runbook**
   - 要多久：半天。
   - 风险：开机自启 / 计划任务会改用户机器；日志占盘。
   - 要拍板：允不允许装计划任务、日志保留几天。
6. **旧进程检测 + 限流**
   - 要多久：2–3 小时。
   - 风险：阈值太低会误伤自己；版本提示要兼容旧 App。
   - 要拍板：限流阈值、是否强提示升级。

## 15.10 1.4.7 交付记录（2026-11-09）

- 交付包：`dist/english-app-1.4.7-remote.apk`，35,113,831 B，md5 `0ca3fe70d16b4eaba8b1c6ff606e5aef`（20:23 重出，含「保存即清游标」补丁；旧 `5cd63242…` 已作废）。
  - **按用户 2026-11-09 决定，本版不 bump 版本号**：versionName 仍 `1.4.6.2`、versionCode 13；包名不变，覆盖安装即可。
- P0 完成度：② 连接串/候选/`/health` 握手 PASS；③ 补发/幂等/指数退避/游标 PASS（换地址游标归零已实测）；④ 状态页 PASS；⑤ 旧端提示 PASS；⑥ 安全 PASS（口令已轮换、README 去明文）；① 公网入口 PARTIAL（runbook / healthcheck / cloudflared-notes 已交付，隧道未实测，需用户本人登录 CF、改 NS）。
- 服务端现状：8765 现 PID 3204，started_at `2026-11-09T19:04:59+08:00`，`/health` 200（protocol=1，capabilities=rewrite/feedback/sync_push/sync_cursor）；口令在 `tools/cardback-editor/.cardback_token`（未跟踪），旧口令已作废（带 `CF-Connecting-IP` 实测 401）。
- 本版修掉的坑：读响应体 `ProtocolException` 被吞成「空数据」→ 现在冒泡重试；换地址后沿用旧游标会漏行 → `serverRev` 归零，且**保存连接串时就清**（`resetCursorForEndpointChange()`，杀进程也不丢）；手填地址多行粘贴 → 只取第一行；冷启动首拉全量 → 记录已见 revision（N1）。
- 验证证据：141 单测 0 失败；功能闸 T1–T9、定点复测 R1–R8 全 PASS；独立验收 **88/100「可交付」**（原 78，B1/B2 修复后复核）。报告与截图在 `_recon/1.4.7/`（gitignored）。
- 未验证（下版或真机再做）：WiFi⇄4G 来回切换、电脑重启、24h soak、远程四链路。**4G 单程已验证 PASS**（2026-11-09 20:50，WiFi 关闭、仅 rmnet 路由，冷启动后 20:50:43 `/health` 200、20:50:46/47 `/sync/pull?since_rev=272` 200，全程走 Quick Tunnel）。真机已装 1.4.7（2026-11-09 20:23 覆盖安装；状态页已连接、实际连接公网域名、电脑端 1.4.2/19:04:59）。

## 15.11 1.4.7.1 交付记录（2026-11-10）

- 交付包：`dist/english-app-1.4.7.1.apk`，33,379,406 B，md5 `e1993c417d2f88fd01cbc1c6f981692b`；versionCode 14 / versionName 1.4.7.1 / DB 21。
- 三条线：F1 反馈闭环（评委第 7 维「诉求落实」、两段式通过、禁令硬约束、两轮不过交 unverified 历史最佳）；S1 服务端加固（`_guard` 401→403→429、`/sync/*` 独立桶、`sync_store` 原子写/坏文件重建/冲突保留/行数上限）；A1 App（版本号、未复核角标全链路、JobScheduler 后台同步）。
- 验收后补丁：`normalize()` 允许「末行旁注、倒数第二行有箭头」；润色后按最终稿复扫用户禁令，命中禁令不当「已复核」交付；单行文本禁令检查不再跳空；禁令扫描识别否定式提及（「不像词根那样拆」不算违规）；评委维度名做近似归一（`像人谐话`→`像人说话`）；记录页 `attempts` 取真实轮次。`tests/test_feedback.py` 25/25。
- 验证证据：
  - Android 141 单测 0 失败；F1 19、S1 契约 30、cardback 18/53/20 全 PASS。
  - 功能闸 T1–T9 全 PASS（`_recon/1.4.7.1/test-report.md`）：版本展示、状态页 10 字段、未复核角标 + 反向对照、后台任务 force-run 真发 `/sync`、404/401/429 + 冷却期零请求、冷启动、无新 ANR/崩溃。
  - 20→21 覆盖升级实测：先装 1.4.7 得 `dbver=20 / words=5228`，`install -r` 1.4.7.1 后 `dbver=21 / words=5228`，logcat 无 Room/FATAL（`upgrade-test.sh`、`db-old/`、`db-new/`）。
  - JobScheduler 实测：`JOB #u0a192/23063 .../.sync.BackgroundSyncJobService`，PERIODIC 15m/flex 5m、PERSISTED、RUNNABLE。
  - 真实链路：重启后跑 `ephemeral`（instruction=用谐音联想，别用词根词缀）→ 4 步交付、末行 `——>`、`unverified=false`。
  - 三词回放（补丁后 `_recon/1.4.7.1/after-b1.json`）：chronic 2 轮 219.2s → **1 轮 177.0s**（B1 格式误杀消失）；hemisphere 1 轮 86.6s（六维全 10）；cathedral 首轮稿质量低（方法选对 5）重试一次 312.9s，p95 312.9s 属生成波动；交付率 100%、白卷 0、unverified 0。
  - 干净中文复验：`live-20261110-ephemeral-cn`（instruction「别用词根词缀拆，换个方法；最后一行提一嘴词根就行」）→ 1 轮通过，七维 9/9/8/9/9/8 + 诉求落实 9，`method=sound`、`unverified=false`。修掉了上一版提交端乱码导致「评委没看懂原话」的存疑。
  - 独立验收：初评 82/100（F1 80 / S1 88 / A1 78）；B1/B2/B3 处理后复评 **89/100「可交付」**（F1 87 / S1 88 / A1 91，三条阻塞全部关闭）；复核记录见 `_recon/1.4.7.1/acceptance-report.md` 末尾。
- 服务端现状：8765 PID 51580，started_at `2026-11-10T01:01:23+08:00`，`/health` 200，能力四项；已加载出包后的服务端补丁（否定式禁令 / 维度错字归一 / attempts 投影）。
- 回滚：DB 21 无降级迁移，旧包不能覆盖安装回去，必须先卸载 / 清 App 数据（进度会丢）；README 与 `docs/decisions-1.4.7.1.md` 已写明。
- 未验证：WiFi⇄4G 来回、电脑重启、24h soak、远程四链路；真实库里 `unverified=true` 的端到端仍是注入数据验证，没有自然产生的未复核卡；真机尚未装 1.4.7.1（等用户点头）。
