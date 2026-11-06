# 1.4.2 卡背清单 + 电脑端编辑器 · 独立验收测试报告

> 测试角色：独立测试员（不参与开发、不改源码、不改规格）。
> 结论：**98 / 100**（A 30/30 · B 28/30 · C 20/20 · D 15/15 · E 5/5）。否决项 **K1–K5 全部未触发**。发现 1 个 P1 级契约偏差（B6）、1 个 P2 级偶发故障。

## 0. 元信息

| 项 | 值 |
| --- | --- |
| 执行时间 | 2026-11-06 21:26 – 22:14（Asia/Shanghai；模拟器内部时区 UTC） |
| 仓库 | `D:\deepseek harness\work_anki_enligish` |
| git HEAD | `231c3ab` docs: 交接文档更新到 1.4.2 已交付状态（前序：`14a9ced` / `5b4c390` 1.4.2 出包 / `cc115f7` / `295fb16` …） |
| 验收规格 | `docs/test-spec-1.4.2.md`<br>sha256 = `b24aa818b516fe955144b0f59379293ed7b5f45a2cd0a55c7f7f734d7b0af5d1`<br>与 `docs/test-spec-1.4.2.md.lock` **完全一致**（开工时、收工前各校验一次，均一致 → 规格未被篡改） |
| 设备 | `emulator-5554`（AVD `mf`，Android 14 / API 34，1080×2340，density 440，时区 UTC）<br>**`adb devices` 全程只有这一台，未出现真机 `10.103.216.69:5555` / `10AF941PZJ00AKH`** |
| App | `com.kaoyan.wordhelper` versionCode **5** / versionName **1.4.2**<br>设备上 `base.apk` sha256 `354bcef5d71a987b7c8b72ae5f6d284834a28a12d86a659faa28d3a141d39b99` = `dist/english-app-1.4.2.apk`（**测试期间未重装、未 clear data**） |
| 电脑端服务 | `python tools/cardback-editor/server.py`，`0.0.0.0:8765`（模拟器经 `10.0.2.2:8765` 访问） |
| 取证方法 | ① 截图逐像素比对：裁掉状态栏 `crop((0,110,1080,2340))` 后与 `docs/shots/base-1.4.1/` 同名图比较，统计变化像素占比与最大通道差（阈值 **0.5%**）；② 结构用 `uiautomator dump` 取 text+bounds；③ toast 用连拍 8–10 张 + PIL 按 toast 区域（y1880:2080, x150:930）均值/标准差挑帧；④ 编辑器用 Playwright + 本机 Chrome 驱动真实页面（非 mock）。 |
| 证据目录 | `docs/shots/test-1.4.2/`（截图）、`artifacts-test/`（清单样本、DB 前后快照、服务日志、脚本） |

## 1. 结论总表

| 编号 | 项目 | 结论 | 分值 | 证据 | 备注 |
| --- | --- | --- | --- | --- | --- |
| A1 | 与 1.4.1 逐像素一致 | ✅ 通过 | 12/12 | `A1-front.png` `A1-back.png` `A1-back-bottom.png` | front 变化 0 像素；back 175 px = **0.0073%**（max 差 4）；back-bottom 299 px = **0.0124%**（max 差 4）→ 全部远低于 0.5% |
| A2 | 五块内容/顺序/缩进 | ✅ 通过 | 8/8 | `A1-*.png` + `uiautomator` dump（见 §2.2） | 顺序 词→音标→分隔线→释义→记忆思路→常用搭配→例句；标题 x=110、块内正文 x=138（Surface 内边距 10dp≈27.5px）与 1.4.1 一致 |
| A3 | 有进度时 EF 区块 | ✅ 通过 | 5/5 | `A3-back.png` `A3-back-bottom.png` `A3-ef-crop.png` | EF 为最后一块；其上有一条通栏分隔线（y≈1381–1383，颜色 219,222,229 = onSurface 14%），面板底色 primary 8%（237,240,251）；正文 `EF系数因子：2.60` / `相对基准2.50偏移：+0.10` / `当前间隔：2天 · 复习次数：1`；顶部状态由「未安排」变「今日」 |
| A4 | 无进度不显示 EF、不留空洞 | ✅ 通过 | 5/5 | `A1-back-bottom.png`（对比 A3） | 同一份默认清单（ef 块 visible=true），词条无进度时 EF 面板与分隔线整段不绘制，底部像素与 1.4.1 基线一致（0.0124%），**无空框、无留白** |
| B1 | 调换顺序即刻生效 | ✅ 通过 | 8/8 | `B1-back.png` `B1-study.png` `phone-cardback-after-B1.json` | 发布 rev 9（mean/memory 互换）→ 词库点刷新 → 手机 `files/cardback.json` 顺序变 `word,ipa,div1,memory,mean,…`，卡背「记忆思路」出现在「释义」之前；无需重装/重启 |
| B2 | 隐藏块 | ✅ 通过 | 6/6 | `B2-back.png` `B2-back-bottom.png` `phone-B2.json` | 隐藏 collocations（rev 10）→「常用搭配」整块消失，其后「例句」坐标 (138,1405,942,1625) 与默认清单下完全一致 → 无空隙 |
| B3 | 改标题 | ✅ 通过 | 4/4 | `B3-back.png` `phone-B3.json` | memory.title="记法"（rev 11）→ 卡背标题文字变「记法」(110,1048,198,1112)，位置与默认标题相同 |
| B4 | 改颜色 + 暗色可读 | ✅ 通过 | 6/6 | `B4-back.png` `B4-back-dark.png` | memory.style.color=warning（rev 12）→ 标题转红（error 色），释义仍为蓝；`cmd uimode night yes` 深色模式下红标题与深色卡面对比清晰可读；已 `uimode night no` 还原 |
| B5 | 字号 S/M/L | ✅ 通过 | 4/4 | `B5-back.png` `phone-B5.json` | mean 设 L：正文框高 71→81 px（×1.14≈1.15）、宽 435→496；memory 设 S：行高 55→50 px（×0.9）；两处标题高度均 64 px **不变** |
| B6 | revision 递增 & 未变不重渲染 | ❌ **失败** | **0/2** | `B6-back.png` `B6-r1-*.png` `B6-retry-*.png` | 发布与线上内容完全相同的清单（rev 14）后连续 4 次刷新，**每次都重写** `files/cardback.json`（mtime 1793972535→…→1793972551）并弹「已更新到第 14 版」→ 实现里没有任何 revision 比较，违反契约 §6。视觉无副作用：`B6-back.png` vs `A1-back.png` 154 px = 0.0064% |
| C1 | 服务关闭 | ✅ 通过 | 5/5 | `C1-toast-4.png` `C1-back.png` | 停服务后刷新 → toast「电脑没连上：检查电脑上的服务开着没、手机和电脑是不是同一个网」；手机 `files/cardback.json` mtime 未变；卡背像素与上一版一致（146 px = 0.0061%） |
| C2 | 非法 JSON 拒收 | ✅ 通过 | 5/5 | `C2a-1..6.png` `C2b-1..6.png` `C2c-1..6.png` `C2-back.png` | 三种坏包：a) `{"schemaVersion":1,"revision":99}`（无 blocks）→「这份结构不能用：清单里一个块都没有（已保持原样）」；b) `blocks:[]` → 同文案；c) schemaVersion 99 + 合法 blocks →「这份结构不能用：这份结构是 99 版做的，当前 App 只认 1 版（已保持原样）」。三次文件 mtime 均未变、卡背未变（138 px = 0.0057%） |
| C3 | 未知 type 跳过 + 提示 | ✅ 通过 | 4/4 | `_toast-c3-ok.png` `C3-back.png` `C3-*.png` | 追加 `REWRITE_BUTTON` 块（rev 15）→ 电脑端返回 `warnings: ["block #12 (id=rewrite): 未知类型 REWRITE_BUTTON（手机端会跳过）"]`；手机 toast「卡背结构已更新：第 15 版，11 块；跳过不认识的块：REWRITE_BUTTON」；手机配置 12 块但渲染 11 块，卡背未变（133 px = 0.0055%），logcat FATAL/ANR = 0 |
| C4 | 空内容块不渲染 | ✅ 通过 | 3/3 | `C4-back-bottom.png` `phone-C4.json` | phrases/synonyms 设标题「短语测试」「近义词测试」（该词这两项无数据，rev 16）→ 全屏 dump 中**搜不到**「短语/近义词/同根」任何文字，卡背底部与默认清单逐像素一致（270 px = 0.0112%）→ 连标题一起不渲染，无空框 |
| C5 | 失败后重启仍上一版 | ✅ 通过 | 3/3 | `C5-after-restart-front.png` `C5-after-restart-back.png` | 服务关闭状态下 `force-stop` + 冷启（约 35 s）→ 进入学习页正常显示 `appropriate`；卡背像素与 C1 时一致（176 px = 0.0073%）；手机配置未变；无 FATAL |
| D1 | 启动脚本 + 地址 | ✅ 通过 | 4/4 | `D-editor-overview.png`、`artifacts-test/server2.log`、`bat.log` | 双击 `启动编辑器.bat`（chcp 65001 → cd 到脚本目录 → `py -3`/`python server.py`，失败才 pause）→ 服务起在 0.0.0.0:8765，控制台给出「浏览器打开：http://127.0.0.1:8765/」「手机填这个地址：http://10.105.85.173:8765/cardback.json」；实测 `http://127.0.0.1:8765/` 与 `http://10.105.85.173:8765/` 均 200；页面右上角亦显示局域网地址 + 复制按钮 |
| D2 | 加块 / 隐藏 / 拖动排序 | ✅ 通过 | 4/4 | `D2-before.png` `D2-after-add.png` `D2-after-hide.png` `D2-after-drag.png` | 组件库点「记忆思路」→ 12 块；取消勾选 collocations → 预览注记「显示 8/12 块 · 1 块已隐藏 · 3 块该词无内容」且预览中「常用搭配」消失；按住最后一行拖到首位 → 列表顺序与预览首段都变成「记忆思路」 |
| D3 | 改字预览立刻变 | ✅ 通过 | 3/3 | `D3-after-title-edit.png` | 改 memory 标题为「记法速查」，未保存/未发布，预览 `#cb-body` 文本立即包含「记法速查」 |
| D4 | 发布到手机 | ✅ 通过 | 2/2 | `D4-after-publish.png`、`/api/state` | 点「发布到手机」→ toast「已发布 revision 17 · 手机上点刷新即生效」；`/api/state` revision 16→17；`/cardback.json` 内容同步（块顺序、标题、collocations visible=false 全部落盘） |
| D5 | 不做手机端没有的功能 | ✅ 通过 | 2/2 | §2.13 扫描结果 | 全页无 `<input type=color>`（0 个）、无绝对定位/自由画布（`#preview-stage` 内 absolute 元素 0 个、`#cb-card` 为 static 306px）、无 contenteditable、无 HTML5 draggable；颜色只能从 6 个语义 token 里选，字号只能 S/M/L/留空；组件库只列契约里的 11 种块 |
| E1 | README 使用说明 | ✅ 通过 | 3/3 | `README.md` §「卡背编辑工具（1.4.2 已实现）」L151–205 | 有启动方式（双击 bat 或 `python tools/cardback-editor/server.py`）、端口 8765、电脑与手机各自的地址写法、模拟器写 `10.0.2.2:8765`、刷新图标位置与「不用重装不用重启」、回到出厂的办法 |
| E2 | HANDOFF 更新 | ✅ 通过 | 2/2 | `HANDOFF.md` 头部与 §1.4.2 | 写明 1.4.2 已开发完成/已出包/只在模拟器验过/真机未装、待办「跑测试 agent + 评分 agent」、1.4.3 方向（重写按钮 + 模型下拉 + 折叠输入框 + 离线标记队列） |

## 2. 分项取证摘要

### 2.1 A1 像素比对（脚本 `artifacts-test/cmp.py`）

```
front        changed_pixels=0     0.000000%  max_channel_diff=0
back         changed_pixels=175   0.007266%  max_channel_diff=4   bbox=(135,538,876,1504)
back-bottom  changed_pixels=299   0.012415%  max_channel_diff=4   bbox=(143,533,759,1507)
```

裁剪区域 1080×2230 = 2,408,400 px；阈值 0.5%（12,042 px），实际最大 0.0124%。最大通道差 4 属文字抗锯齿。截图条件：学习页当前词 `appropriate`、1/20、认词模式；翻面用 `input tap 540 1100`（≤4dp 位移），滚到底用卡内一次 `swipe 540 1350 → 540 500`。

### 2.2 A2 结构

默认清单卡背的 dump 顺序（text, bounds）：

```
appropriate (110,626,970,690)   音标 /əˈprəʊpriət/ (110,723)
释义 标题 (110,847)             释义正文 (110,944,545,1015)
记忆思路 标题 (110,1048,286,1112)  三条思路 (138,1151 / 1388 / 1570)
常用搭配 …                       例句 …
```

标题 x=110、块内正文 x=138（= 110 + Surface padding 10dp≈27.5px）。「短语 / 近义词 / 同根词」在本词数据上为空（book1 的 5228 词里 phrases/synonyms/rel_words 全部为空），与 1.4.1 行为一致地自动跳过——1.4.2 的 `WordMemorySection` / `WordExtraSection` 与 1.4.1 逐行相同（仅标题改成可空 + 新增默认等于旧值的颜色/字号参数），因此默认清单下渲染等价，A1 的像素一致也印证这一点。

### 2.3 B 组操作方式

每次改清单前 `curl -s http://127.0.0.1:8765/api/state` 记 revision，改完 `POST /api/publish`，再在手机上「词库 → 当前词书 → 最右刷新图标 (966,544)」。B1–B5 全部**只点刷新**，未重装、未重启 App。样本存于 `artifacts-test/B1-swap.json` … `B5-size.json`。

### 2.4 B6 复现（失败项）

1. 发布与线上完全相同的默认清单（revision 14，`artifacts-test/B6-same.json`）。
2. 手机上连续点 4 次刷新，每次间隔约 3 s。
3. 结果：`files/cardback.json` 的 mtime 每次都变（1793972535 → 1793972540 → 1793972545 → 1793972551），toast 每次都显示「已更新到第 14 版」（连拍证据 `B6-r1-*.png` / `B6-retry-*.png`）。
4. 代码定位：`data/repository/CardBackRepository.kt` 的 `refresh()` → `applyRemoteBody()` 全程无 `revision` 比较；`ui/viewmodel/BookManageViewModel.kt` 也没有。契约 `docs/cardback-schema-1.4.2.md` §6 明写「手机端只在 revision 变大时才替换本地清单」。
5. 影响：① 电脑端回退到旧版本后，手机下次刷新会被“静默回滚”；② 每次刷新都重写配置与 lastGood 副本（无意义的存储写入）；③ toast 在没有任何变化时声称“已更新”，对用户是误导。视觉与数据均未损坏，故不构成否决项。

### 2.5 C 组操作方式

C2/C3/C4 需要推送“服务端发布接口会拒绝”的坏包，因此直接覆写 `tools/cardback-editor/published/cardback.json`（服务端 `/cardback.json` 按原样吐字节，本身不校验）——这正是要验证的手机端容错路径；每次覆写后都核对手机 `files/cardback.json` 的 mtime 与内容未变。C1/C5 用 `taskkill /F /PID` 停服务。

### 2.6 D 组操作方式

Playwright（本机 Chrome，headless）打开 `http://127.0.0.1:8765/`，用真实 DOM 操作：点组件库加块、用 checkbox 隐藏、用 mouse.down/move/up 在 `[data-drag-handle]` 上拖动排序、在 `#title-input` 改字、点 `#publish` 发布。脚本 `artifacts-test/editor_D.py`、`editor_D5.py`。

### 2.7 D5 越界扫描明细

* `input[type=color]`：0 个；颜色仅 `select` 的 6 个 token（default/primary/secondary/accent/muted/warning）。
* 字号仅 `select`：空 / S / M / L。
* 间距一个 number 输入（4–32），对应清单的 block spacing，不是任意像素定位。
* `#preview-stage` 内 absolute 定位元素 0 个；`#cb-card` 为 static、宽 306px（固定预览，不可自由摆放）。
* 无 `draggable`、无 `contenteditable`、无自定义 HTML/脚本入口；组件库只有契约的 11 种块。
* 结论：编辑器能做的（加/删/隐藏/改标题/6 色/3 字号/拖动排序/间距/预览/保存草稿/发布/回退）都能在手机端表达，未发现手机端不存在的呈现能力。

## 3. 否决项自查（K1–K5）

| 编号 | 内容 | 结论 | 证据 |
| --- | --- | --- | --- |
| K1 | 学习进度不得被测试改动 | ✅ 未触发 | 测试前 `tb_progress` **0 行**（`PRAGMA quick_check`=ok，`user_version`=14），整库备份 `artifacts-test/db-before/`（sha256 `dd9fac881a1969a623555a85b96470124a853a52ce3912b9d3ac836b37e2fc02`）。A3 需要“有进度”的卡片，曾临时插入 3 行并在 UI 上点过一次「认识」；**收工已用备份整文件还原**，还原后 sha256 与备份**完全相同**、`tb_progress` 仍 0 行、`quick_check`=ok。设备上的学习进度与测试前完全一致。 |
| K2 | 卡背不得变空 | ✅ 未触发 | A/B/C 共 20 余次翻面截图全部有内容；C1–C5 每次坏包/断网后 `*_back*.png` 与上一版逐像素一致（0.005%–0.011%）。 |
| K3 | 不得崩溃 / ANR | ✅ 未触发 | 全程 `adb logcat -d | grep -cE "FATAL EXCEPTION|ANR in com.kaoyan"` = **0**；C3 结束时单独统计也为 0。 |
| K4 | 不得改规格 / 改源码 | ✅ 未触发 | 规格 sha256 开工与收工两次均为 `b24aa818…`，与 `.lock` 一致；`git status --porcelain` 只有未跟踪的 `artifacts-test/`、`docs/shots/test-1.4.2/`（以及本报告），`app/src_repo/**`、`docs/test-spec-1.4.2.md`、`.lock`、`docs/cardback-schema-1.4.2.md` 均未修改；未 commit、未 checkout/reset。 |
| K5 | 不得碰真机 | ✅ 未触发 | `adb devices -l` 全程只有 `emulator-5554`；未出现 `10.103.216.69:5555` 或 `10AF941PZJ00AKH`；所有 adb 写操作都带 `-s emulator-5554`。 |

## 4. 发现的问题（按严重度）

### P1-1 · 手机端没有 revision 闸门（B6 失败，契约 §6 偏差）

* **现象**：清单内容与 revision 都没变时，每点一次刷新都会重写本地配置，并提示「已更新到第 N 版」。
* **复现**：发布与线上完全相同的清单（rev 14）→ 学习页/词库页连点刷新 4 次 → `run-as com.kaoyan.wordhelper stat -c '%Y' files/cardback.json` 的 mtime 每次都变。
* **期望（契约 §6）**：手机端只在 `revision` 变大时才替换本地清单；revision 未变时应跳过（可提示“已是最新”）。
* **实际**：无条件替换，且 toast 声称已更新。
* **疑似层级**：网络/数据层——`data/repository/CardBackRepository.kt` 的 `refresh()`/`applyRemoteBody()` 缺 revision 比较（`ui/viewmodel/BookManageViewModel.kt` 同样没有）。渲染层无关。
* **影响**：电脑端「回退到上一版」后，手机下次刷新会被静默回滚；每次刷新产生无意义写盘；提示语误导。非破坏性，故未触发否决项。

### P2-1 · 服务正常时偶发「电脑没连上」

* **现象**：测试中 3 次出现手机提示「电脑没连上：…」，而同一时刻电脑端日志有对应的 `GET /cardback.json 200`：`[21:41:28]`、`[21:48:08]`，以及 C3 的第一次刷新（第二次点击即成功）。客户端在约 1 s 内失败，**不是** 8 s 超时路径。
* **复现**：无法稳定复现（3 次/约 40 次刷新，≈7%）。
* **期望**：服务在线时刷新应一次成功；或失败时自动重试一次。
* **疑似层级**：网络层——OkHttp 复用连接在服务端重启/半关闭后拿到失效连接，代码没有重试。`CardBackRepository.refresh()` 里 `fetch()` 返回 null 即直接 Failure。
* **影响**：用户看到吓人的错误提示、需要再点一次；数据无风险（旧配置保留）。

### 观察项（不计分、不扣分）

* **O-1**：电脑端 `/cardback.json` 按原样吐 `published/cardback.json`，不做校验（发布接口会校验，但本地手改文件不走校验）。本次 C2 正是用这条路径造坏包；手机端兜住了。若希望“坏配置永远出不了电脑”，可在读取侧加一次 `CardBackCodec` 等价的校验。
* **O-2**：冷启动耗时约 35 s（logcat `Long monitor contention … WordbookFullLoader.loadWordbookCache … 22.849s`），与 C5 无关但影响体验；1.4.1 已存在，不是 1.4.2 引入。
* **O-3**：`docs/shots/base-1.4.1/` 与本次截图同设备同分辨率，逐像素比对成立；换设备/密度时该判定应降级为结构比对（规格已允许）。

## 5. 评分

| 分组 | 明细 | 得分 |
| --- | --- | --- |
| A 外观回归（30） | A1 12 + A2 8 + A3 5 + A4 5 | **30 / 30** |
| B 结构下发（30） | B1 8 + B2 6 + B3 4 + B4 6 + B5 4 + **B6 0** | **28 / 30** |
| C 容错（20） | C1 5 + C2 5 + C3 4 + C4 3 + C5 3 | **20 / 20** |
| D 编辑器（15） | D1 4 + D2 4 + D3 3 + D4 2 + D5 2 | **15 / 15** |
| E 文档（5） | E1 3 + E2 2 | **5 / 5** |
| **合计** | | **98 / 100** |

**扣分点**：仅 B6（−2）——revision 未变时仍替换本地清单，违反契约 §6。

## 6. 收工状态（可继续使用）

* 8765 服务已停止，`netstat` 无监听；端口空闲。
* `tools/cardback-editor/published/cardback.json` = 出厂默认 11 块（revision 18，全部 visible、style=null、标题为默认值）；手机 `files/cardback.json` 已刷新为同一份（revision 18，11 块）。
* 「我的 → 卡背结构地址」仍为 `10.0.2.2:8765`（`user_settings.preferences_pb` 中确认）。
* 数据库与测试前逐字节一致（见 K1）。
* `adb devices` 仅 `emulator-5554`；App 已重新启动到学习页。
* 仓库未提交：新增未跟踪文件仅 `artifacts-test/`、`docs/shots/test-1.4.2/`、`docs/report-1.4.2-test.md`。

## 7. 证据索引

* 截图：`docs/shots/test-1.4.2/`（A1-* / A3-* / B1-* … B6-* / C1-* … C5-* / D-* / `_toast-*.png`）
* 清单样本：`artifacts-test/{B1-swap,B2-hide,B3-title,B4-color,B5-size,B6-same,C3-unknown,C4-empty}.json`、`published-rev14.json`
* 手机侧配置快照：`artifacts-test/phone-*.json`、`phone-final.json`
* 数据库快照：`artifacts-test/db-before/`（测试前）、`db-final2/`（还原后，sha256 相同）
* 服务日志：`artifacts-test/server.log`、`server2.log`、`bat.log`
* 脚本：`artifacts-test/cmp.py`（像素比对）、`cb.py`（state/publish/rollback/UI dump）、`editor_D.py`、`editor_D5.py`、`editor_probe*.py`
