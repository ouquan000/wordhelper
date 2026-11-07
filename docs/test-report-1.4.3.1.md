# 不爱背单词 1.4.3.1 独立测试报告

- 被测包：`dist/english-app-1.4.3.1.apk`（33,792,909 B，md5 `f3cf884f0ce8a3c402fe5831fdb786f2`）
- 装机实测：`com.kaoyan.wordhelper` versionCode 7 / versionName 1.4.3.1（`dumpsys package` 复核，lastUpdateTime 2026-11-07 14:19:34）
- 设备：`emulator-5554`（sdk_gphone64_x86_64，Android 14，headless，系统时间 14:2x GMT = 22:2x CST）
- 电脑端：`http://127.0.0.1:8765`（测试期间未重启、未改动 `tools/rewrite-service/data/`）
- 测试时段：2026-11-07 22:24–22:29 CST
- 测试性质：只读取证 + UI 冒烟；**未改任何源代码、未重新编译、未重启任何服务**

> ⚠️ **本次测试被中途截断**：测到第 3 条后，用户为腾内存打游戏主动关闭了模拟器（父 agent 转达：`adb emu kill` + 杀残留进程，并明确要求不要再启动）。因此第 5–8 条**没有跑**，报告里一律标「未验证」，不做任何推测性结论；能拿到制品级/历史数据侧证的，单独标为「旁证（不构成通过）」。

---

## 一、总览

| # | 测试项 | 结果 |
|---|--------|------|
| 1 | 冷启动能打开，停在卡正面，无崩溃/ANR | **PASS** |
| 2 | 卡正面不出现「重写思路」那一行 | **PASS** |
| 3 | 卡背出现重写行，且位于卡片内部最下方、在三键之上不遮挡 | **PASS**（附带 1 个重要缺陷，见 I1） |
| 4 | 卡背不再出现「EF系数因子」 | **PASS** |
| 5 | 点重写 → 进度条 → 卡背不翻面原地更新 → 出现「上次重写…」「撤销」 | **未验证**（模拟器被用户关闭） |
| 6 | 点撤销 → 卡背恢复上一版（DB rev 递减、source 变化） | **未验证**（模拟器被用户关闭） |
| 7 | 模型下拉可见 5 个模型、可选中并保持 | **未验证**（模拟器被用户关闭） |
| 8 | 输入框可展开输入并提交，instruction 到达服务端 | **未验证**（模拟器被用户关闭） |
| 9 | 普通操作不崩（翻面若干次、切 tab、上一词/下一词） | **部分验证**（只做了 1 次翻面 + 4 次 dump，未做多次/tab/上下词） |
| 10 | 电脑端 `/rewrite` 记录页有统计卡 + 表格 | **PASS** |

**合计：5 条 PASS、0 条 FAIL、4 条未验证、1 条部分验证。**

---

## 二、逐条证据

### 1. 冷启动 —— PASS

操作：`logcat -c` → `am force-stop com.kaoyan.wordhelper` → 确认进程消失 → `monkey -p com.kaoyan.wordhelper -c android.intent.category.LAUNCHER 1`。

- 进程重启成功：旧 PID 7038 → 新 PID 7362（`ps -A | grep kaoyan`）
- 窗口聚焦：`mCurrentFocus=Window{bd47e4a u0 com.kaoyan.wordhelper/com.kaoyan.wordhelper.MainActivity}`
- **崩溃缓冲区为空**：`logcat -d -b crash` 无任何输出
- 主 buffer 中 `FATAL|ANR in|AndroidRuntime|has died|Force finishing` 只命中 monkey 自身的 `RuntimeInit`/`VM exiting with result code 0`，无 App 异常
- 加载完成后停在**卡正面**（截图 `docs/img-1.4.3.1/02-coldstart-card-front.png`）：
  - 认词模式 / 拼写模式、未安排、`appropriate`、`/əˈprəʊpriət/`
  - 提示语「轻触卡片查看释义与例句」
  - 三键：不认识 / 模糊 / 认识；学习进度 1/20
- 未观察到任何「不爱背单词 isn't responding」对话框（也没有点过 Wait）

启动耗时（次要问题 I2）：14:25:40 注入启动 → 14:25:5x 仍停在「准备学习内容中…」→ 14:26:2x 出卡面，约 **35–40 秒**。

### 2. 卡正面没有重写行 —— PASS

`uiautomator dump` 的 text 全集（正面）：

```
['认词模式', '拼写模式', '未安排', 'appropriate', '/əˈprəʊpriət/',
 '轻触卡片查看释义与例句', '不认识', '模糊', '认识', '学习进度', '1/20',
 '学习', '查词', '词库', '我的']
```

逐项断言（原始 dump：`docs/evidence-1.4.3.1/ui-dump-card-front.xml`）：

| 断言串 | 出现在正面？ |
|---|---|
| `重写思路` | ❌ 否 |
| `再重写一次` | ❌ 否 |
| `哪里有问题` | ❌ 否 |
| `DeepSeek` | ❌ 否 |
| `撤销` / `上次重写` | ❌ 否 |

结构上也确认：正面卡片 `[44,378][1036,1735]` 内只有 未安排 / 星标 / 单词 / 音标 / 提示语，**没有 ScrollView、没有重写块节点**（节点树见 `ui-tree-card-front.txt`）。正面因此完全没被重写条占高度，符合 spec「正面不占高度」。

### 3. 卡背重写行位置 —— PASS（含重要缺陷 I1）

轻触卡片中部 `tap 540 1050` 翻面，`uiautomator dump` 节点树（`docs/evidence-1.4.3.1/ui-tree-card-back.txt`）：

```
卡片容器          View        [44,378][1036,1735]
├ 未安排/星标      …
├ 内容滚动区        ScrollView [110,626][970,1277]  SCROLL
│  ├ appropriate   [110,626][970,690]
│  ├ /əˈprəʊpriət/ [110,723][328,778]
│  ├ 释义          [110,847][198,911]
│  ├ adj. 恰当的，合适的 [110,944][545,1015]
│  ├ 记忆思路       [110,1048][286,1112]
│  └ ① 先抓住…      [138,1151][942,1277]      ← 被裁剪，见 I1
└ 重写块           View        [99,1327][981,1680]   CLICK
   ├ 再重写一次                     [99,1338][397,1448]  (Button)
   ├ DeepSeek V4.1 Flash ▾         [562,1338][981,1448] (Button)
   ├ 哪里有问题？点开写（留空＝整段重写） [99,1438][981,1570]
   ├ 上次重写 11-07 14:23           [99,1592][821,1636]
   └ 撤销                          [821,1559][981,1669] (Button)
三键面板           View        [44,1763][1036,1939]   ← 独立容器
   ├ 不认识 [66,1796][364,1906]
   ├ 模糊   [392,1796][689,1906]
   └ 认识   [717,1796][1014,1906]
学习进度 1/20 + ProgressBar
```

三条位置断言全部成立：

1. **属于卡片内部**：重写块 `[99,1327][981,1680]` 完全落在卡片 `[44,378][1036,1735]` 内（底边 1680 < 1735，撤销按钮底边 1669 < 1735）。
2. **在三键面板之上**：重写块底 1680，三键面板顶 1763，**间隔 83px，零重叠**，三键可正常点击。
3. 文案三件套齐全：模型下拉「DeepSeek V4.1 Flash ▾」+ 输入占位「哪里有问题？点开写（留空＝整段重写）」+ 动作按钮。

**关于按钮文案「重写思路」→ 实测是「再重写一次」**：这是状态相关的两种文案，不是缺陷。对 APK 内 15 个 dex 反查字面量得到：

```
重写思路     -> classes10.dex:utf8
再重写一次   -> classes10.dex:utf8
```

两个串都在包里，且 `docs/spec-1.4.3.1.md` 第 8 行用户原话用的是「你那个重写思路哪个组快…」，spec 正文的验收项只要求「正面无重写条 → 翻面出现且贴卡底」，**并未规定按钮字面**。本次因为 `appropriate` 已有历史重写，所以按钮呈「再重写一次」；首次重写时的「重写思路」文案**未在屏幕上观测到**（列入未验证范围外的说明，见 I3）。

### 4. 卡背没有「EF系数因子」 —— PASS

双证据：

- UI 层：卡背 dump 全文不含 `EF系数`，text 全集里只有 释义/记忆思路/①②③ 正文，没有任何系数行。
- 制品层：解包 `dist/english-app-1.4.3.1.apk`，对 `classes*.dex`（15 个）+ `resources.arsc` 按 UTF-8/UTF-16LE 双编码搜索 `EF系数`，**0 命中**（`docs/evidence-1.4.3.1/apk-string-scan.txt`）。该字符串已彻底不在产物内，与 spec 改动 #6（`EF_STATS` → `-> Unit`）一致。

### 5–8. 重写 / 撤销 / 模型下拉 / 输入框 —— 未验证

模拟器在第 4 步取证后被用户关闭（`adb devices` 变空、无 qemu 进程），且被明确要求不得重启，因此这四项**没有跑过**，不给 PASS/FAIL。

为免信息浪费，下面只列**旁证**（说明「上一次成功链路是通的」，但**不构成本轮通过**）：

- 服务端 `GET /rewrite/status` seq 12：`word=appropriate, rev=9, status=ok, model=ds-v41-flash, updatedAt=2026-11-07T22:23:00+08:00, clientId=w1-1794061234224`，`approach` 为「① 先抓住 appropriate 的核心段 propri…」三段。
- DB（`.db` + `-wal` 一起拉取，`docs/evidence-1.4.3.1/db-baseline-dump.txt`）`tb_word_memory` word_id=1：`rev=9, source=user, updated_at=1794061381330`（= 2026-11-07 22:23:01 CST），`approach` 与上条 seq 12 文本**逐字一致**；`prev_approach` 已写入（= seq 11 那版「① 看骨架：appropriate 切成 ap-propri-ate 三段…」）。
- `tb_rewrite_queue` 仅一行：`id=5, word_id=1, word=appropriate, instruction='', model=ds-v41-flash, status=done, updated_at=1794061381330`，与 `tb_word_memory.updated_at` 同一毫秒。
- 我动手前抓的首屏截图（`t01-initial`，未改动任何状态）里，卡背显示 **100% 进度条 + 「上次重写 11-07 14:23」+「撤销」**。

→ 说明「跑完 → 落库（rev/source/prev）→ 卡背渲染出上次重写与撤销」这条链在 22:23 那次是成立的；但**「不翻面原地更新」这个动作本身、撤销的实际回退、下拉的 5 个模型、输入框提交 instruction，本轮都没执行**。
→ 其中 `instruction` 一路尤其缺乏历史证据：`tools/rewrite-service/data/queue.json` 现有 12 条，`instruction` **全部为空串**；`/rewrite/records` 接口甚至不返回 `instruction` 字段（12 条均无该 key）。所以第 8 条必须实机补测，无法用历史数据代替。

制品级旁证（同样不构成通过）：第 7 条的 5 个模型在 `classes13.dex` 里能查到 5 个显示名 —— `DeepSeek V4.1 Flash`、`Step 5`、`GLM 5.3`、`GPT-6.1 Sol`、`Grok 4.7`，以及模型 id `portdan-gpt`、`portdan-grok`（`docs/evidence-1.4.3.1/apk-model-names.txt`），与服务端现网的 `ds-v41-flash` / `step5` / judge `wb/cn:glm-5.3` 对得上。这只能证明「包里有 5 个模型」，证明不了「下拉能展开、能选中、能保持」。

### 9. 普通操作不崩 —— 部分验证

- 已做：冷启动、1 次翻面（tap 540,1050）、4 次 `uiautomator dump`，全程进程存活（PID 7362 始终在 `ps` 中）、崩溃缓冲区始终为空、没有弹出 ANR。
- 未做：多次来回翻面、切「词库/我的/学习」再回来、下一词/上一词。
- 说明一处易误读：我在滚动测试时执行 `input swipe` 后 `adb` 报 `error: closed`，随后 `adb devices` 为空。**这不是 App 崩溃**——随后收到通知确认是用户主动 `adb emu kill` 关模拟器（腾内存打游戏）。崩溃缓冲区在此之前一直是空的。

### 10. 电脑端 `/rewrite` 记录页 —— PASS

`curl -s http://127.0.0.1:8765/rewrite` → **HTTP 200**，13,957 B，`text/html; charset=utf-8`（原始页面已存 `docs/evidence-1.4.3.1/server-rewrite-page.html`）。

- `<title>重写记录</title>`，`<h1>重写记录</h1>`，副标题「生成 → 润色 → 评分，每条标记一行，按最近更新倒序」
- **统计卡 4 个**：`今天完成`(id=today-done) / `今天失败`(today-failed) / `正在跑`(running) / `待跑`(pending)
- **表格 7 列**：单词 / 模型 / 阶段 / 状态 / 时间 / 思路 / 失败原因 / 操作
- 两个按钮：`立即刷新`(id=refresh) / `暂停自动刷新`(id=pause)，右上角 stamp 显示「加载中…」
- 数据来自同源接口 `fetch("/rewrite/records?limit=50")` 与 `fetch("/rewrite/status")`；直接打 `/rewrite/records` → **HTTP 200**，`total=12`，12 条记录，字段 `seq/word/model/state/stage/status/rev/approach/prev/clientId/createdAt/updatedAt/attempts/error`

取证方式说明：用 curl 取 HTTP + DOM 校验，**没有开浏览器窗口**（避免抢前台，用户正在打游戏）。因此「浏览器里肉眼看是对的」这一步没做，但页面结构与数据源均已确认。

---

## 三、问题清单

### 【重要】I1 — 卡背「记忆思路」正文被重写条挤掉，三分之二不可见

**现象**：卡背上「记忆思路」只显示 ① 的前约 3 行（在「意思是"自己」处被硬切），②③ 完全看不到，裁剪处没有渐隐/省略号/「展开」提示。截图 `docs/img-1.4.3.1/03-card-back-rewrite-row.png`。

**量化**：
- 卡片是**固定高度** `[44,378][1036,1735]`（正面/背面容器同一矩形）
- 卡背内容滚动区 `ScrollView [110,626][970,1277]`，可视高仅 **651px**
- 「记忆思路」正文 TextView `[138,1151][942,1277]`，可视高仅 **126px**（≈3 行）；而该文本约 80 字、按当前宽度需 4 行以上才排完 ①，②③ 各还需 2–3 行，实际约 10 行
- 重写块占 `[99,1327][981,1680]`，**353px** + 50px 间隔

**成因判断**：1.4.3.1 的改动 #1 把重写条从卡片外挪进卡背内部（spec 第 18 行 `backActions` 槽），卡片高度没变，于是这 353px 是从内容区抠出来的。1.4.3 时重写条在卡外，卡背内容不被挤占，①②③ 可以同屏。

**复现步骤**：
1. 冷启动 App（`am force-stop` + `monkey`）
2. 等卡正面出现，轻触卡片中部（约 540,1050）翻到背面
3. 看「记忆思路」段落 —— ①②③ 三段只剩 ① 的前三行，下方直接接「再重写一次」按钮

**影响**：这是背单词的核心产出（三段式记忆推导）。第 ② 步（为什么用这个方法）和 ③ 步（闭环落到词义）是整套方法论的落点，被默认藏起来对实际背词是实打实的损失；而且没有视觉提示告诉用户「这里还能滚」。

**待确认**：`ScrollView` 带 `scrollable="true"`，理论上能滚出后面内容，但**滚动能否真的露出 ②③ 本轮未验证**（测到 swipe 时模拟器被关）。补测时请优先确认：能滚 → 降级为「次要（可用但易漏）」；不能滚 → 升级为「阻断」（核心内容不可达）。

### 【次要】I2 — 冷启动到卡面约 35–40 秒，无进度反馈

14:25:40 启动，14:25:5x 仍停在「准备学习内容中…」，14:26:2x 才出卡面。期间只有一行静态文案，用户不知道是在加载还是卡死。
注：headless 模拟器 + 19.2MB DB（5226 张记忆卡）环境，真机应更快；建议真机上复测一次再定级。

### 【次要】I3 — 按钮文案随状态在「重写思路 / 再重写一次」间切换，易误判

两个串都在 APK 内（`classes10.dex`）。spec 与用户原话都用「重写思路」指代整个功能，而实机有历史时显示「再重写一次」，本轮对照测试时就先误以为文案不符。建议在 `test-spec` 里写明两态，避免下一位测试者重复踩。

### 【信息】I4 — `/rewrite/records` 不返回 `instruction`，第 8 条难以核对

12 条记录均无 `instruction` 字段，验证「输入框提交的 instruction 到达服务端」只能翻 `tools/rewrite-service/data/queue.json`，而该文件只会保留每条词的最新一条，历史 instruction 不可回溯。建议 records 接口补出 `instruction`（哪怕只给长度或截断值），否则这条验收每次都只能靠一次性观测。

---

## 四、结论

本轮在模拟器可用的时间窗内完成了冷启动、卡正面、卡背结构、EF 系数清除、电脑端记录页五项验证，**5 条 PASS、0 条 FAIL**，没有发现任何崩溃、ANR 或启动失败，也没有发现「功能做了但做错」的硬伤；同时确认了重写条确实被塞进卡片内部最下、与三键零重叠（间隔 83px），这一版的核心意图是达成的。

但有两条必须先说清楚：一是**第 5–8 条（原地更新、撤销、5 个模型下拉、输入框提交）压根没跑**，模拟器被用户中途关掉、且明确不许重启，所以这三项核心新功能目前只有历史数据与 APK 字面量的旁证，不能当作通过；二是发现一个**重要缺陷 I1**——卡背为塞下重写条，把「记忆思路」的多段正文裁到只剩 ① 的前三行，②③ 默认看不见且无任何提示，是否能滚动补看本轮也未来得及确认。

**交付判断**：从「App 能正常打开、不崩、新行位置对」的角度，**可以交给用户先上真机做基础冒烟**（这几项已经实证，风险低）；但**不建议就此宣布 1.4.3.1 验收通过**——请先在能开模拟器的时间窗里补测第 5/6/7/8 条，并顺手确认 I1 的滚动能否救回正文。如果补测结果显示卡背内容滚不动、②③ 彻底不可达，那 I1 就要从「重要」升为「阻断」，这一版不宜发布。

---

## 附：证据文件索引

| 文件 | 内容 |
|---|---|
| `docs/img-1.4.3.1/01-splash.png` | 冷启动「准备学习内容中…」 |
| `docs/img-1.4.3.1/02-coldstart-card-front.png` | 冷启动完成后的卡正面（无重写行） |
| `docs/img-1.4.3.1/03-card-back-rewrite-row.png` | 卡背：重写行 + 被裁剪的记忆思路 |
| `docs/evidence-1.4.3.1/ui-tree-card-front.txt` | 正面节点树（含 bounds） |
| `docs/evidence-1.4.3.1/ui-tree-card-back.txt` | 卡背节点树（含 bounds，I1 的量化依据） |
| `docs/evidence-1.4.3.1/ui-dump-card-front.xml` | 正面 uiautomator 原始 dump |
| `docs/evidence-1.4.3.1/ui-dump-card-back.xml` | 卡背 uiautomator 原始 dump |
| `docs/evidence-1.4.3.1/db-baseline-dump.txt` | `.db`+`-wal` 一起读出的 tb_word_memory / tb_rewrite_queue 基线 |
| `docs/evidence-1.4.3.1/server-rewrite-records.json` | `GET /rewrite/records` 原始 JSON（12 条） |
| `docs/evidence-1.4.3.1/server-rewrite-page.html` | `GET /rewrite` 原始 HTML |
| `docs/evidence-1.4.3.1/apk-string-scan.txt` | APK 内 dex/arsc 字面量扫描（EF系数 0 命中） |
| `docs/evidence-1.4.3.1/apk-model-names.txt` | APK 内 5 个模型显示名与 id 上下文 |
