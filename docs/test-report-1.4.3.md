# 1.4.3「记忆思路重写」验收测试报告

- 被测包：`com.kaoyan.wordhelper` versionName **1.4.3** / versionCode **6**（`dumpsys package` 实测）
- 设备：emulator-5554（1080×2340，时区 GMT）
- 电脑端：`http://127.0.0.1:8765`（tools/cardback-editor/server.py 托管的 rewrite-service，真实模型调用）
- 测试时间：2026-11-07 11:21 – 12:25（GMT+8）
- 依据：`docs/spec-1.4.3.md` §6 验收 8 条
- 结论：**7 条 PASS，1 条 FAIL**（§6.8 五模型未全部跑通；§6.6 附一条 App 端行为说明）

## 0. 总览

| # | 验收项 | 结论 | 一句话证据 |
|---|--------|------|-----------|
| 6.1 | 断网标记不丢；联网后自动补发 | **PASS** | 地址指 9999（不可达）时标记落 `tb_rewrite_queue` status=pending，冷启动后仍在；改回 8765 并回学习页（resume→syncRewrite）后自动变 queued，服务端收到同一词标记并开跑 |
| 6.2 | 冷启动该词三行变新，学习进度不变 | **PASS** | 冷启动后 `increase` 三行 `source=user rev=1`，`prev_approach` 为原种子文本；卡背显示新三行；学习进度仍 1/19，`tb_progress` 行未变 |
| 6.3 | 撤销回上一版，且只能回一次 | **PASS** | rev1→撤销：`source=seed rev=0 prev=NULL`；rev2→撤销：`source=user rev=1`（回的是 rev1 文本）；撤销后 prev 置空、按钮消失，再点同坐标 DB 无变化 |
| 6.4 | 种子 version+1 重导不覆盖已重写词 | **PASS** | 推送 version=2 种子后冷启动：seed 词 require/skim 刷成新种子文本；user 词 appropriate/increase 原样保留；代码 `WordRepository.kt:1035-1051` 显式跳过 `source='user'` |
| 6.5 | 评分两次不过：原卡保留、标记失败、可重试 | **PASS** | abolish/glm53、absorb/portdan-gpt 均「两轮都没过」→failed；卡背显示「重试重写 / 上次没成功：电脑端没生成出合格思路」，原卡 `rev/source/approach` 未动；点重试 → 新队列行 queued，服务端重新收标 |
| 6.6 | 同词连点两次只算一条，后写指令覆盖 | **PASS** | 同一批 POST 两条同词标记（FIRST / SECOND）→ 服务端只留 1 条，instruction 为后写的 SECOND；App 端 PENDING 态再点=取消、QUEUED/DONE/FAILED 再点=重发（见 6.6 说明） |
| 6.7 | 电脑不在线时打开 App：卡背正常、不报错、不转圈 | **PASS** | 地址指 9999 冷启动：48s 出卡、卡背「记忆思路」正常显示本地三行，无 FATAL/ANR，无转圈无错误弹窗 |
| 6.8 | 下拉 5 个模型全部跑通；deepseek=max，其余 high | **FAIL** | 只有 ds-v41-flash 稳定出结果；step5 修复后能出内容但两轮评审均 0 分；glm53、portdan-gpt 两轮评审不过；portdan-grok HTTP 502 / 长时间挂起。配置侧 ds=max、其余=high 正确 |

**阻断级缺陷：0 个。** 核心链路（默认 DeepSeek V4.1 Flash）端到端可用；但 §6.8 不通过，发布前需就模型可用性做决策。

---

## 1. 测试环境与方法（可复现）

```bash
# adb 全路径，PATH 里没有 adb；Git Bash 必须关掉路径改写
ADB=D:/Android/Sdk/platform-tools/adb.exe
export MSYS_NO_PATHCONV=1          # 否则 /data/... 被改写成 Windows 路径

# 应用库（注意是 kaoyan_words.db）
$ADB shell "run-as com.kaoyan.wordhelper sqlite3 -header -column \
  /data/data/com.kaoyan.wordhelper/databases/kaoyan_words.db \"select ...\""

# UI：先 dump 到 //sdcard 再 pull 解析（adb shell cat 会 GBK 乱码；uiautomator 不能并发调用）
$ADB shell uiautomator dump //sdcard/ui.xml && $ADB pull //sdcard/ui.xml ./ui.xml
```

- 底部 tab 实测坐标：学习 `(126,2208)`、我的 `(945,2208)`；「卡背结构地址」在「我的」页要上滑 5 次才出现，保存按钮约 `(912,587)`。
- Toast 不进入 uiautomator，均以「点击后 0.7–1.0s 内 screencap」取证。
- **环境与任务书不符**：任务书称「App 内地址已是 10.0.2.2:8765」，实测 `card_back_url` 初始为空（DataStore `files/datastore/user_settings.preferences_pb` 只有 `card_seed_version`），输入框只显示 placeholder `http://192.168.1.10:8765`。本报告中的地址均由我在「我的 → 卡背结构地址」手动填入并保存（DataStore 复核过落盘值）。
- 8765 服务是**多客户端共享**的：期间另有测试者注入标记（clientId 形如 `probe-step5-12000` / `probe-step5-full`），服务在 11:39:59、11:48:41 被外部重启两次（log「重写服务启动…」，运行中任务变「重启接续」）。本报告只采信 App 自己产生、`clientId` 形如 `w{wordId}-{epochMs}` 的记录，其余标为环境噪声。

---

## 2. 逐条实测

### §6.1 断网标记不丢；联网后自动补发 — PASS

1. 地址改 `10.0.2.2:9999`（该端口无服务），保存后 DataStore 复核 `10.0.2.2:9999`。
2. 在 `increase`（word_id=2）卡背点「重写思路」：
   - Toast：**「已记下，等电脑连上再发」**（screenshot `s63_offlinemark.png`）
   - DB：`tb_rewrite_queue` 新增 `id=6 word=increase model=ds-v41-flash status=pending`
   - UI：按钮「已标记 · 再点取消」，状态行「已标记 · 待上传（再点一次取消）」
3. **断网态冷启动**（force-stop + am start）：行仍在 `pending`，UI 仍显示「已标记 · 待上传（再点一次取消）」，无崩溃。
4. 地址改回 `10.0.2.2:8765` → 保存 → 切回「学习」tab（触发 `LearningScreen.kt:171 LifecycleResumeEffect → viewModel.syncRewrite()`）：
   - 手机队列 `id=6 … status=queued`
   - 服务端 `/rewrite/status` 出现 `increase / ds-v41-flash / pending` → 随即开跑（日志 `12:07:21 [increase] 生成 17.1s`）
   - 日志 `12:05:47 收标 1 条：接受 1 拒绝 0`

即「断网标记不丢 + 联网自动补发」成立（补发不需要冷启动，回到学习页即可）。

> 补充：这次补发跑出来的结果是 failed（见 §6.8/缺陷 D2 评审 JSON 截断），属于另一条问题，不影响「补发」本身的结论。

### §6.2 冷启动该词三行变新，学习进度不变 — PASS

- 11:37:23 在 `increase` 卡背点「重写思路」（模型 DeepSeek V4.1 Flash，指令 `line3 too weak try band again`，EditText 读回值一致）→ 队列 `id=3 status=queued` → 服务端 done `seq=5 rev=1`，日志「第1轮 润色 22.9s 评分 16.5s 最低 8 分；完成 total 79.1s」。
- 11:39:5x force-stop + am start，约 45s 后：
  - DB：`word_id=2 increase source=user rev=1 approach_len=173 prev_approach_len=223`，`prev_approach` 头部为「① 先看前缀 in-…」= 原种子文本
  - 卡背「记忆思路」显示新的三行（①②③ 与 DB 文本一致，screenshot）
  - 学习进度 `1/19`；`tb_progress` 仍只有 word_id=1 的 3 行，`last_review_time=1794022524920` 未变
  - 队列行 `status=done`，卡背出现「上次重写 11-07 03:40」+「撤销」

> UI 显示的 `03:40` 是 GMT 时区（模拟器 `date` 返回 GMT），不是缺陷。

### §6.3 撤销回上一版，且只能回一次 — PASS

**（a）rev1 → 种子**：11:41:1x 点「撤销」→ Toast「已撤销回上一版」；DB `word_id=2 source=seed rev=0 prev_approach=NULL approach_len=223`（回到种子原文），队列行删除；约 3s 后按钮回「重写思路」、「撤销」消失；在消失位置再点一次，DB 无任何变化 → 只能回一次。

**（b）rev2 → rev1**（关键：不是只回种子）：11:49:49 点「重试重写」发起的第二次成功重写（seq=12，约 11:58 完成），resume 同步后 DB `source=user rev=2 approach_len=251 prev_approach_len=173`（prev = 上一版 rev1 文本，173 字符），卡背「上次重写 11-07 03:58」+「撤销」；点撤销 → `source=user rev=1 approach_len=173 prev_approach=NULL`，队列行删除，按钮回「重写思路」。

### §6.4 种子 version+1 重导不覆盖已重写词 — PASS

**代码级**（`data/repository/WordRepository.kt:961 upsertDraftsForBook`）：

```kotlin
val userSourcedWordIds = wordMemoryDao.getUserSourcedWordIds().toHashSet()
…
if (wordId in userSourcedWordIds) return@mapNotNull null   // 1035-1051：source='user' 的词直接跳过
```

触发条件 `KaoyanWordApp.kt:102`：`cardSeed != null && cardSeed.version > settingsRepository.cardSeedVersion()`。种子文件优先读外部路径（`data/model/CardSeed.kt` 注释）：`/sdcard/Android/data/<pkg>/files/cet6_cards.json`。

**实测**：把 assets 里的 `cet6_cards.json`（version=1，5228 词）复制为 version=2，改写 3 个词做标记（appropriate → `【种子覆盖测试】…` + `collocations=[seed-overwrite-marker]`；require / skim → `【种子刷新测试】…`），push 到设备外部目录后冷启动。结果：

| 词 | 用例 | source | rev | 长度 | 内容 |
|----|------|--------|-----|------|------|
| appropriate | user 词（不该被覆盖） | user | 2 | 148 | 保持用户重写文本（**未**出现「种子覆盖测试」） |
| increase | user 词（不该被覆盖） | user | 1 | 173 | 保持用户重写文本 |
| require | seed 词（应刷新） | seed | 0 | 49 | `【种子刷新测试】require 的新种子思路第一行…` |
| skim | seed 词（应刷新） | seed | 0 | 41 | `【种子刷新测试】skim 的新种子思路 A…` |

即：重导确实执行了（seed 词被替换），且 `source='user'` 的词被跳过。

### §6.5 评分两次不过：原卡保留、标记显示失败、可重试 — PASS

- **真实两轮评审不过**（非调用失败）：
  - `abolish / glm53`：第 1 轮最低 0 分 → 第 2 轮仍不过 → `11:46:55 失败（验收没过）：两轮都没过：拆分按读音切 a|bo|lish…`
  - `absorb / portdan-gpt`：第 1 轮最低 3 分 → 第 2 轮最低 6 分 → `11:53:54 失败（验收没过）：两轮都没过：ab- 先释为…`
- **UI**：冷启动后 `increase` 卡背显示「**重试重写**」+「上次没成功：电脑端没生成出合格思路」（screenshot `s30.png`），原卡正面与 DB 均未变（`source=seed rev=0 approach_len=223`）。
- **可重试**：点「重试重写」→ 队列 `id=5 word=increase model=ds-v41-flash status=queued`，服务端于 11:49:49 重新收标（clientId `w2-1794023388030`），11:56:16 开跑，约 11:58 完成 seq=12（即 §6.3 里拿到 rev2 的那次）。
- 再补一条我自己的词：一次 failed 结果同步回手机后，`tb_rewrite_queue` 行变 `failed`、错误文案「电脑端没生成出合格思路」，`tb_word_memory` 仍 `user rev=1 approach_len=173` 保持不变 → **失败不动原卡**。

### §6.6 同词连点两次只算一条，后写指令覆盖 — PASS

- **服务端（数据路径）**：同一批 POST 两条同词标记 `ability`（先 `FIRST 指令 先写`、后 `SECOND 指令 后写`，不同 clientId）→ `accepted` 两条 → `queue.json` 中该词**只有 1 条**，且 `instruction` 为后写的 `SECOND…`、clientId 为后写那条（`t66-b-…`）。
- **代码级**：`tools/rewrite-service/store.py` 的 `add_marks` 对同 clientId 幂等、`_enqueue` 对同词 pending 覆盖旧条、对 running 旧条标 `superseded`。
- **App 端**（`RewriteBar.kt` onClick）：`PENDING` 再点 = `onCancel()`（取消，这点符合任务书「同一词再点能否取消」）；`QUEUED / DONE / FAILED` 再点 = 重新 `onMark(instruction)`，服务端按同词幂等规则只留一条、后写覆盖。

> 注意：在 PENDING 态下「连点两次」在 App 表现为**取消**而不是覆盖，若验收按字面「后写指令覆盖」理解，需要走 QUEUED 态再点（或等新包把 PENDING 改成覆盖，父 agent 已排期）。

### §6.7 电脑不在线时打开 App：卡背正常、不报错、不转圈 — PASS

- 地址 `10.0.2.2:9999`（无服务）→ force-stop + am start：
  - 3s：splash「准备学习内容中…」（正常启动过程）
  - 48s：卡面正常，点开卡背「记忆思路」正常显示本地三行（`① 先看 in-：它在这里不指"在里面"…`），无转圈、无错误弹窗（唯一弹窗是既有的「手势快捷操作」引导）
  - logcat 无 `FATAL EXCEPTION` / ANR（只有系统侧 `WindowManager DeadObjectException` 与 NetworkMonitor 探测失败，与本 App 无关）
  - 队列标记保持 `pending`，未丢失

### §6.8 下拉 5 个模型全部跑通；deepseek=max，其余 high — FAIL

**配置侧（正确）**：`tools/rewrite-service/providers.py:33-66`

| modelId | 名称 | provider / 模型 | reasoning |
|---|---|---|---|
| ds-v41-flash | DeepSeek V4.1 Flash | wb `cn:deepseek-v4.1-flash` | **max** |
| step5 | Step 5 | wb `cn:step-5-preview` | high |
| glm53 | GLM 5.3 | wb `cn:glm-5.3` | high |
| portdan-gpt | GPT-6.1 Sol | portdan-gpt `gpt-6.1-sol` | high |
| portdan-grok | Grok 4.7 | portdan-grok `grok-4.7` | high |

发送逻辑：wb 走 `body["reasoning_effort"]`（providers.py:212-213），portdan 走 `body["reasoning"]={"effort": …}`（223-224）。UI 下拉 5 项实测无「倍率」字样，切换后 DataStore `rewrite_model` 落盘正确（如 `ds-v41-flash`、`step5`）。

**运行侧**：

| 模型 | 结果 | 证据 |
|---|---|---|
| ds-v41-flash | ✅ 跑通 | appropriate seq=3 rev=1；increase seq=5 rev=1、seq=12；accommodate seq=11（评分 10/10/10/9/9） |
| step5 | ❌ | 修复前 3 次「空 content（finish_reason=length）」（11:33–11:47）；12:02 用新服务复测 `abroad`：能出内容但两轮评审均最低 0 分（「拆分成立 0」）→ `12:07:04 失败（验收没过）`；另一路 `appropriate/step5` 在修复后 done |
| glm53 | ❌ | `abolish` 两轮均不过（拆分按读音切、谐音牵强）→ failed |
| portdan-gpt | ❌ | `absorb` 第 1 轮最低 3 分、第 2 轮最低 6 分 → failed |
| portdan-grok | ❌ | `abstract` 三次 HTTP 502；12:02 复测 `absolute` 三次 HTTP 502，12:19 才落 failed（耗时 ~15 分钟，期间一直占着并发槽） |

结论：**5 个模型没有全部跑通**。ds 之外 4 个模型「调用能发出，但产物过不了评审或被上游拒绝」，其中 grok 还会长时间占用并发槽。

---

## 3. 缺陷与问题清单

### 一般（需修复/需决策）

**D1. 4/5 模型不可用（对应 §6.8 FAIL）**
- 现象：step5 输出两轮评审 0 分；glm53、portdan-gpt 两轮评审不过；portdan-grok 三次 HTTP 502（单条耗时约 15 分钟，期间占着并发槽）。
- 影响：下拉里 4 个选项实际不可用；用户选中后要等 3–5 分钟才拿到失败。
- 证据：`service.log` 11:46:55 / 11:53:54 / 12:07:04 三次「失败（验收没过）」，以及 grok 的 `HTTP 502 error code: 502`。
- 建议：要么修 prompt/评审口径，要么先下架不达标模型（`/models` 只回可用项）。

**D2. 评审输出 JSON 被 3000 token 截断 → 已通过的词被判 failed**
- 现象：`increase` 12:08:51 失败，原因「评分结果不是 JSON：{"scores":{"拆分成立":9,"方法选对":9,"谐音过关":10,"链条闭合":9,"文本充足":9},"pass":true,"issues":[…」（字符串在中途被截断）。分数其实已全过（最低 9）。
- 根因：`tools/rewrite-service/pipeline.py:165` 评审 `max_tokens=3000`，而 issues 文本较长时 JSON 被截断；`extract_json` 失败直接抛 `PipelineError`，整轮不重试，App 侧只显示兜底文案「电脑端没生成出合格思路」。
- 复现：让某词评审输出长 issues（本例为 increase，指令「line3 too weak try band again」）。
- 建议：评审 max_tokens 与生成对齐（12000）或限制 issues 长度；JSON 解析失败应作为「本轮回炉」而不是整条失败。

**D3. 「重写思路」工具条出现在卡正面（与 spec §5.1 不符）**
- 现象：未翻面时也能看到「重写思路 + 模型下拉 + 折叠输入框」，正面截图与卡背截图一致（screenshot `s21.png` 正面）。
- 代码：`ui/screen/LearningScreen.kt:591` 的 `RewriteBar(...)` 位于 `WordCard` 之外（`WordCard` 内 `isFlipped` 在 1598 行）。
- spec §5.1 要求「只在卡背，正面不动」。
- 建议：把 RewriteBar 挪进 WordCard 的 `isFlipped` 分支，或按 `isFlipped` 做 `AnimatedVisibility`。

### 建议（体验/健壮性）

**S1. 撤销后 UI 回位有 1–3s 延迟**：点撤销瞬间按钮仍是「再重写一次」、撤销仍可点（截图 s38），约 3s 后才回「重写思路」。期间再点一次是空操作（DB 不变），不致命。

**S2. 撤销不复位服务端 rev，prev 可能指向用户已放弃的版本**：rev2 撤销回 rev1 后，再次重写会得到 rev3，其 `prev_approach` 指向上次撤销掉的 rev1 文本；用户视角「上一版」与数据里的 prev 不一致。建议撤销时把服务端该词 rev 归零或记录 undo 标记。

**S3. 切换 tab 会丢掉未发送的指令**：输入框内容用 `remember(wordId)`，离开组合即重置；用户写了半截切去看词库再回来就没了。建议用 ViewModel 保存草稿（至少当前词）。

**S4. PENDING 态再点=取消 ≠ spec §6.6 的「后写覆盖」**：spec 字面要求连点两次只算一条且后写指令覆盖；当前实现 PENDING 再点是取消，覆盖只在 QUEUED/DONE/FAILED 态发生。（父 agent 已在改这一处，待新包复验。）

**S5.「手势快捷操作」弹窗每次冷启动都出现**（「知道了」不持久化）——既有问题，非 1.4.3 引入。

### 观察（不计入 1.4.3 缺陷）

- **O1** 冷启动主线程被 `WordbookFullLoader.loadWordbookCache(WordbookFullLoader.kt:178)` 持有 19–20s（`Skipped 1249–1282 frames`、Davey 21–22s），splash 到出卡约 25–45s；既有性能问题。
- **O2** 8765 服务被外部重启两次（11:39:59、11:48:41），运行中任务显示「重启接续」；step5 首轮「空 content」根因是 `max_tokens=6000`（已改为 12000，`pipeline.py:116/145`），12:02 起复测已不再出现空 content。
- **O3** `/rewrite/results` 返回的 `clientId` 为 null（store.py 写 results 时漏字段，已修但要重启才生效）；App 侧守卫对 null 宽容，迟到的旧结果可能被应用——本报告未把该场景计入 8 条结论。
- **O4** 用 `am start` 在已有任务上再起实例时，新实例显示「暂无单词 0/0」（dumpsys 见同 Task 两个 Hist）；正常冷启动（force-stop 后）不受影响。
- **O5** 任务书与环境不符：App 内 `card_back_url` 初始为空，需手动填；已在本报告中用 UI 填入 `10.0.2.2:8765`。

---

## 4. 测试数据残留（模拟器）

- 设备外部种子：`/sdcard/Android/data/com.kaoyan.wordhelper/files/cet6_cards.json` = **version 2 测试种子**（含 `【种子覆盖测试】/【种子刷新测试】/seed-overwrite-marker` 标记）。清理：`adb shell rm` 该文件后清 App 数据即可回到 version 1 资产。
- `tb_word_memory` 现状：word1 appropriate `user rev=2`（148 字，另一路 step5 结果）、word2 increase `user rev=1`（173 字）、word3 require / word4 skim 为 version 2 种子文本（49 / 41 字）。
- 其它被服务端跑过、但未同步到手机的词：abandon、adapt、abolish、absorb、ability、abstract、absolute、accommodate、abroad。
- `tb_rewrite_queue`：`id=2 appropriate step5 done`、`id=6 increase ds-v41-flash failed`。
- 测试脚本与截图：`%LOCALAPPDATA%\Temp\anki-test\`（parse_ui.py / dump.sh / tap.py / s*.png / seed_v2.json）。

## 5. 复现缺陷的最短步骤

| 缺陷 | 步骤 |
|---|---|
| D1 | 服务在线 → 卡背选 Step 5 / GLM 5.3 / GPT-6.1 Sol / Grok 4.7 → 点「重写思路」→ 等 3–13 分钟 → 卡背「上次没成功」，`service.log` 见对应「失败（验收没过）」/`HTTP 502` |
| D2 | 选 DeepSeek → 指令填 `line3 too weak try band again` → 标记 increase → 等完成 → `service.log` 出现「评分结果不是 JSON：{\"scores\":{…9…},\"pass\":true…」 |
| D3 | 冷启动 → 不翻面 → 正面即可看到「重写思路」按钮、模型下拉、折叠输入行 |
| S1 | 任一成功重写后点「撤销」→ 1s 内截图：按钮仍「再重写一次」、撤销仍在 |
| S3 | 展开输入框写一段 → 切「词库」→ 切回「学习」→ 输入框内容清空 |
