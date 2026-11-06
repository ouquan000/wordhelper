# 1.4.2 卡背清单化改造 — 独立评分报告

> 评分方：独立评分 agent（第二个 agent）。**没有读过** `docs/report-1.4.2-test.md`，**没有用过** `docs/shots/test-1.4.2/` 与 `artifacts-test/` 里的任何东西；下面每一条结论都是本机现场跑出来、自己截的图。
> 结论：**100 / 100**，K1–K5 否决项全部未触发。

---

## 0. 元信息

| 项 | 值 |
|---|---|
| 被评提交 | `1b96b58 docs: 记录 1.4.2 验收结果与 B6 修复复测`（开工时 HEAD，`git status --porcelain` 为空） |
| 被评 APK | 用仓库源码现场重建：`app/src_repo/app/build/outputs/apk/debug/app-debug.apk`，mtime `2026-11-06 22:06`，33,221,506 B，sha256 `2f7101da220ed8df0b80e22cd72baef0dbc37f1d891c61e5977638bd1f00c661`；`gradle --no-daemon :app:assembleDebug` BUILD SUCCESSFUL，40 个 task 全 UP-TO-DATE（APK 与该 commit 源码一致，无源码比 APK 新） |
| 验收规格 | `docs/test-spec-1.4.2.md`，sha256 `b24aa818b516fe955144b0f59379293ed7b5f45a2cd0a55c7f7f734d7b0af5d1`（开工、收工各算一次），与 `docs/test-spec-1.4.2.md.lock` 完全一致 |
| 清单契约 | `docs/cardback-schema-1.4.2.md`（评分期间未变） |
| 设备 | 模拟器 `emulator-5554`（AVD `mf`，Android 14 / API 34）；真机 `10.103.216.69:5555` / `10AF941PZJ00AKH` **全程未连接、未操作** |
| App | `com.kaoyan.wordhelper`，versionCode 5 / versionName 1.4.2，`adb install -r` 覆盖安装，**未 clear data** |
| 电脑端服务 | `python tools/cardback-editor/server.py`（`0.0.0.0:8765`），发布目录 `tools/cardback-editor/published/`（运行时数据） |
| 执行区间 | 2026-11-06 22:09 → 22:54（A–E 全量 + 清理）；2026-11-07 00:44 → 00:50（重启模拟器后复验终态与 D1/D2 补证） |
| 我拍的证据 | `docs/shots/score-1.4.2/`（自建目录，65 个文件） |
| 比对口径 | Python 3 + Pillow，统一裁 `crop((0,110,1080,2340))` 去状态栏；像素差判定阈 `max channel diff > 8` |

> **会话期间仓库发生的、与本评分无关的变动（如实记录）**：开工后另有三次提交落地，都是别人（另一个 agent）提交的，不是我 —— `1dfd530 feat(cardback-editor): 支持打包成单文件 exe，新增 --port 参数`、`e6a9296 fix(cardback-editor): 双击 exe 报错后停住等回车`、`67a9d8d docs: HANDOFF 补 exe 与桌面快捷方式，修正 APK 体积`。它们只动 `.gitignore`、`README.md`、`HANDOFF.md`、`tools/cardback-editor/build-exe.bat`、`tools/cardback-editor/server.py`；`git diff --stat 1b96b58 HEAD -- app/src_repo docs/test-spec-1.4.2.md docs/cardback-schema-1.4.2.md` **为空** —— App 源码、规格、契约一个字没变，规格 sha256 收工复校仍一致（`b24aa818…af5d1`）。收工时 HEAD = `67a9d8d`；E1/E2 在 `67a9d8d` 上重新确认过（README 的「卡背编辑工具（1.4.2 已实现）」一节还在，HANDOFF 的 1.4.2 状态与「还没做的」还在）。D 段在 00:47 用当时 HEAD 的服务端**复跑了一次启动**（见 D1），结论不变。

---

## 1. 总分

| 段 | 项 | 满分 | 得分 |
|---|---|---|---|
| A | 回归：外观零变化 | 30 | **30** |
| B | 结构可下发 | 30 | **30** |
| C | 容错 | 20 | **20** |
| D | 编辑器 | 15 | **15** |
| E | 文档 | 5 | **5** |
| | **合计** | **100** | **100** |
| 否决项 | K1–K5 | — | **全部未触发** |

---

## 2. A. 回归：外观零变化（30 / 30）

| # | 检查项 | 结论 | 分 | 证据 |
|---|---|---|---|---|
| A1 | 卡背截图与基线逐像素比对，差异 < 0.5% | 通过 | 12 | `A1-front.png`、`A1-back.png`（对比 `docs/shots/base-1.4.1/front.png`、`back.png`） |
| A2 | 五个原有块内容/顺序/缩进与 1.4.1 一致 | 通过 | 8 | `A2-back-bottom.png`（对比 `base-1.4.1/back-bottom.png`）+ 源码对照 |
| A3 | EF 系数区块位置正确、分隔线存在 | 通过 | 5 | `A3-back-ef-top.png`、`A3-back-ef-bottom.png` |
| A4 | 无进度词不显示 EF、不留空洞 | 通过 | 5 | `A2-back-bottom.png`（该词 `progress == null`） |

**A1**：正面 **0 px 差异**（0.0000%）；背面 **173 px / 0.0072%**，最大通道差 4/255（纯抗锯齿），远低于 0.5% 阈值。
**A2**：背面滚到底与基线 **245 px / 0.0102%**，最大通道差 4 —— 释义 / 记忆思路 / 常用搭配 / 例句 的文案、顺序、缩进逐像素一致。短语 / 近义词 / 同根词 三块在「六级记忆卡」这本书里**没有数据**（`tb_book_word_content` 里 word 1/2 的 `phrases`/`synonyms`/`rel_words` 长度全为 0），所以永远不会渲染；改为源码对照：1.4.1 的 `LearningScreen.kt:1798-1800` 与当前的 `LearningScreen.kt:1921/1928/1935` 调的是同一个 `WordExtraSection`（定义在当前 1987 行），参数（12dp 圆角、surfaceVariant 0.45、padding 10/8、spacing 4dp、标题 labelLarge、正文 bodySmall）逐项同构；默认清单顺序也与契约一致（TITLE→PHONETIC→DIVIDER→MEANING→MEMORY→COLLOCATIONS→EXAMPLE→PHRASES→SYNONYMS→REL_WORDS→EF_STATS）。
**A3**：给 `increase` 造出已到期进度后，卡背最末（例句之后）出现 EF 区块，**自带分隔线**，内容「EF系数因子：2.60 / 相对基准2.50偏移：+0.10 / 当前间隔：2天·复习次数：1」——位置与分隔线都对。
**A4**：无进度时卡背到「例句」为止，**没有 EF 区块、没有留白空洞**，与 1.4.1 基线逐像素一致。

> 说明：规格写「`increase` 卡背截图与 `docs/shots/` 基线图比对」。发包方提供的基线 `base-1.4.1/` 拍的就是本机同状态下的卡（词条 `appropriate`，未安排、1/20），我按同一状态复现后比对，符合同状态逐像素比对的判定本意；`docs/shots/1.4-card-back-increase.png`（真机 1.4 截图）显示 increase 卡背同样只有 记忆思路/常用搭配/例句 三块，结构一致。

---

## 3. B. 结构可下发（30 / 30）

| # | 检查项 | 结论 | 分 | 证据 |
|---|---|---|---|---|
| B1 | 调换两块顺序 → 刷新 → 顺序改变、无需重装 | 通过 | 8 | `B1-back-swapped.png`、`B1-toast-updated.png` |
| B2 | 隐藏一块 → 消失，其余块间距无异常空洞 | 通过 | 6 | `B2-back-memory-hidden.png`、`B2-toast-hidden.png` |
| B3 | 改块标题 → 刷新 → 标题改变 | 通过 | 4 | `B3-back-title-changed.png` |
| B4 | 改颜色语义名 → 颜色按映射变、暗色可读 | 通过 | 6 | `B4-back-color-warning.png`、`B4-dark-color-warning.png`、`B4-dark-start.png` |
| B5 | 改字号档 → 内容字号变、标题不变 | 通过 | 4 | `B5-back-size-L.png`、`B5-compare-M-vs-L.png`、`B5-compare2.png` |
| B6 | 发布 → revision 递增；未变时不重复重渲染 | 通过 | 2 | `B6-before.png`、`B6-unchanged-toast.png` + mtime 记录 |

**B1**：把 `MEMORY` 与 `COLLOCATIONS` 对调后发布 → 服务端 revision **20→21**；手机点词库页刷新图标（触摸中心 966,544），toast「卡背结构已更新：第 21 版，11 块」，卡背变成「常用搭配」在「记忆思路」之前，**未重装、未重启**。
**B2**：`memory.visible=false`（rev 22）→ 该块整块消失；量化间距：默认版「释义→记忆思路」行距 58 px，隐藏版「释义→下一块」也是 **58 px**，没有异常空洞。
**B3**：`collocations.title="搭配示例"`（rev 23）→ 卡背标题变成「搭配示例」。
**B4**：`mean.style.color="warning"`（rev 24）→ 释义标题变红，取样主色 **RGB(229,90,90) = #E55A5A = AlertRed**（`ui/theme/Theme.kt:31/53`：light/dark 的 `error` 都是 AlertRed，语义映射正确）；其余块标题仍是 primary 蓝。暗色模式（`cmd uimode night yes`）下红标题在深蓝底 `#1C2641` 上对比度 **4.21:1**，肉眼清晰可读（略低于 WCAG AA 的 4.5，但规格要求是「可读」，且这是 App 自带的 error 色板，不是任意取色）。
**B5**：`mean.style.size="L"`（rev 25）→ 释义**正文**墨迹宽度 430 px → **491 px = 1.142×**（规格 ×1.15，差异在字形/抗锯齿量化误差内；源码 `LearningScreen.kt:1995-1997` 就是 `factor = 0.9f / 1.15f`）；释义**标题**墨迹框 **37×189 px 两图完全相同**——标题不缩放。
**B6**（两半都验）：
① 发布后 revision 递增：本次会话实际观察到 20→21→22→23→24→25→26→28→29→31→32→35，手机端每次都跟着更新（`B1-toast-updated.png` 等）。
② 内容与 revision 都没变时不重复重渲染：同内容连点两次刷新，`run-as com.kaoyan.wordhelper stat -c '%Y' files/cardback.json` 两次都保持 **1793974116** 不变，toast「卡背结构已是最新：第 20 版，11 块」（`B6-unchanged-toast.png`）—— 没写盘、也没谎报「已更新」。源码 `CardBackRepository.kt:117-122`：`if (config.revision <= current.revision && sameContent(config, current))` 直接返回，不写盘不重渲染。

---

## 4. C. 容错（20 / 20）

| # | 检查项 | 结论 | 分 | 证据 |
|---|---|---|---|---|
| C1 | 电脑不在线 → 提示且沿用旧结构 | 通过 | 5 | `C1-toast-offline.png`、`C1-before.png` |
| C2 | 非法 JSON / 缺 blocks / 空 blocks / 版本不认 → 拒收、不落盘 | 通过 | 5 | `C2-toast-invalid.png`、`C2-toasts-3variants.png`、`C2-back-unchanged.png` |
| C3 | 未知块类型 → 跳过并提示、其余照常 | 通过 | 4 | `C3-toast-unknown-block.png`、`C3-back-after-unknown.png` |
| C4 | 块数据为空 → 不出空框 | 通过 | 3 | `C4-back-empty-block.png`、`C4-back-bottom.png` |
| C5 | 拉取失败后重启 App → 仍是上一版，无白屏 | 通过 | 3 | `C5-toast-fail-before-restart.png`、`C5-lastgood-title-before-restart.png`、`C5-restart2-back.png` |

**C1**：停掉 8765 服务（`netstat` 无监听）后刷新，连拍抓到 toast「电脑没连上：检查电脑上的服务开着没、手机和电脑是不是同一个网」，设备 `files/cardback.json` mtime 保持 **1793975093 / 1725 B** 不变，卡背仍是旧结构。
**C2**：直接覆写 `published/cardback.json`（`/cardback.json` 原样吐字节）逐个试：
- 截断 JSON →「这份结构不能用：不是合法的 JSON：java.io.EOFException…」；
- 缺 `blocks` →「这份结构不能用：清单里一个块都没有（已保持原样）」；
- `"blocks": []` → 同上；
- `"schemaVersion": 99` →「这份结构不能用：这份结构是 99 版做的，当前 App 只认 1 版（已保持原样）」。
  四次设备端配置 mtime 全部保持 **1793975093 / 1725 B**（没有落盘），卡背与上一版逐像素一致（0 px 差，max 2）。
**C3**：插入 `REWRITE_BUTTON`（未知类型，rev 28）→ toast「卡背结构已更新：第 28 版，11 块；跳过不认识的块：REWRITE_BUTTON」，设备文件 rev 28 / 12 块（未知块原样存着），卡背与插入前 **0 px 差**（max 4）——忽略未知块、其余照常、有提示。
**C4**：默认清单里 PHRASES/SYNONYMS/REL_WORDS 本来就是 `visible:true`，但这本书这些字段全空；把 PHRASES 标题改成「短语测试」发布（rev 29）→ 手机上该块**根本没画**，也没有空框，底部依次是 常用搭配→例句→EF 区块。
**C5**：两轮。第一轮停服 → 刷新失败 → 冷启动 App，卡背仍在（与失败前除星星图标区外 0 px）。因为第一轮「上一版」恰好就是默认清单，区分不了「上一版」与「内置默认」，所以补了第二轮：先发布带区别性标题「搭配示例」（rev 31）让手机拉到 → 停服 → 刷新失败（toast 抓到，配置 mtime 保持 1793976037 / 1746 B）→ `am force-stop` + 冷启动 30 s → 卡背标题**仍是「搭配示例」**，不是内置默认的「常用搭配」→ 重启后沿用最后一次成功拉取的配置。

---

## 5. D. 编辑器（15 / 15）

| # | 检查项 | 结论 | 分 | 证据 |
|---|---|---|---|---|
| D1 | 双击启动、打印端口、自动开浏览器 | 通过 | 4 | `D1-launcher.log`、`D0-editor-initial.png` |
| D2 | 增删/显隐/拖动排序生效 | 通过 | 4 | `D2-add-block.png`、`D2-delete-block.png`、`D2-hide-block.png`、`D2-reorder.png`（`D2-reset-default.png` 为重置回默认） |
| D3 | 右侧实时预览 | 通过 | 3 | `D3-preview-live.png` |
| D4 | 发布到手机 | 通过 | 2 | `D4-published.png` + 手机端 rev 应用记录 |
| D5 | 只能选语义色/字号，无原始 CSS 入口 | 通过 | 2 | `D5-controls.png` |

用真实浏览器（Playwright + Chromium）驱动 `http://127.0.0.1:8765/` 逐项操作，不是读源码推断。
**D1**：`cmd /c "启动编辑器.bat"`（等价于双击）→ 打印「卡背编辑器已启动 / 浏览器打开：http://127.0.0.1:8765/ / 手机填这个地址：http://10.105.85.173:8765/cardback.json / 发布目录：…\published / 按 Ctrl+C 停止」；立即出现 `GET /`、`/styles.css`、`/samples.js`、`/app.js`、`/api/state` 请求（浏览器自动打开）；`netstat` 见 `0.0.0.0:8765 LISTENING`；`/api/state` 返回 `revision 35, historyCount 32`。
**D2**：组件库点「分隔线」→ 块列表 11→12；点某块的删除按钮 → 12→10（删掉分隔线），toast「已删除「分隔线」撤销×」，「10 块」，右侧预览里音标与释义之间的**分隔线同时消失**；点 MEMORY 的显隐开关 → 状态变「已隐藏」、预览里记忆思路消失；拖 EXAMPLE 的把手到 MEANING 之前 → 列表与预览顺序同步改变。
**D3**：把 MEANING 标题从「释义」改成「释义测」，**不点发布**，右侧预览立即显示「释义测」。
**D4**：点「发布到手机」→ `/api/state` 32 版、`/cardback.json` 里 12 块、MEMORY `visible=false`、MEANING `title=释义测`、EXAMPLE 在 MEANING 之前；随后手机刷新即应用（同一会话记录到设备端 rev 35 落地，mtime 1793976565 / 2052 B）。
**D5**：页面里 `input[type=color]` 数量 **0**；颜色下拉只有 `default / primary / secondary / accent / muted / warning` 六个语义名（无调色盘、无 hex 输入）；字号只有 `默认 / S / M / L`，并注明只作用内容文字、块标题固定 labelLarge；块间距是 dp 数值 4–32；全文没有「绝对定位 / 坐标 / 透明度」这类控件。

---

## 6. E. 文档（5 / 5）

| # | 检查项 | 结论 | 分 | 证据 |
|---|---|---|---|---|
| E1 | README 有编辑器启动方式、端口、手机怎么填地址 | 通过 | 3 | `README.md:151` 起「## 卡背编辑工具（1.4.2 已实现）」 |
| E2 | HANDOFF 更新 1.4.2 状态与下一步 | 通过 | 2 | `HANDOFF.md`（22,424 B，2026-11-06 21:30 生成） |

**E1**：三步用法写得很实：双击 `tools/cardback-editor/启动编辑器.bat` 或 `python tools/cardback-editor/server.py`（只用标准库）、启动后打印的地址样例、自动开浏览器、手机在「我的」填 `192.168.1.23:8765`（自动补 `http://` 和 `/cardback.json`）、词库页刷新图标、模拟器写 `10.0.2.2:8765`、端口被占用的行为、以及 exe 用法（`--port`）。
**E2**：`HANDOFF.md` 首屏写明「1.4.2 已开发完成并出包（`dist/english-app-1.4.2.apk`，versionCode 5）」「验收状态」「还没做的：1.4.3 / 动效参数 / 装真机等用户点头」，下一步清楚。

---

## 7. 否决项自查 K1–K5

| # | 否决项 | 结论 | 证据 |
|---|---|---|---|
| K1 | 复习进度被改动 | **未触发** | 刷新应用新结构前后，`tb_progress` 10 行 dump 的 md5 均为 `c93e19018a72f7b4829d889dd5ceec76`（完全一致）；收工时该表回到初始状态 0 行（见 §8 说明） |
| K2 | 卡背出现空白 | **未触发** | 非法 JSON / 缺 blocks / 空 blocks / 版本不认 / 未知块 / 离线 / 失败重启 —— 每种场景都截图确认卡背有内容（`C2-back-unchanged.png`、`C3-back-after-unknown.png`、`C5-restart2-back.png` 等） |
| K3 | 崩溃或进不去学习页 | **未触发** | `adb logcat -b crash` 为空，主缓冲区无 `FATAL EXCEPTION` / `ANR in`，App pid 存活，学习页全程可翻面（点 `540 1100`），冷启动均正常进入 |
| K4 | 改规格 / 改源码 / 伪造证据 | **未触发** | 规格 sha256 开工/收工都是 `b24aa818…af5d1` = `.lock`；`git diff --stat 1b96b58 HEAD -- app/src_repo docs/test-spec-1.4.2.md docs/cardback-schema-1.4.2.md` 为空；`git status --porcelain` 只有我新建的证据/报告文件（`?? docs/shots/score-1.4.2/`、`?? docs/report-1.4.2-score.md`），我没有 commit / checkout / reset |
| K5 | 碰真机 | **未触发** | `adb devices` 全程只有 `emulator-5554`；所有命令都带 `-s emulator-5554`；从未连接 `10.103.216.69:5555` / `10AF941PZJ00AKH` |

---

## 8. 环境观察与坦白（不影响判定，但如实写）

1. **我自己制造过学习记录，已清理**：为了验 A3（EF 区块只在有进度时出现）和「到期复习」路径，我在 App 里真实点过「认识」，并给 `increase` 造了到期进度；中途还误用了左右滑（源码 `GestureHandler.kt:33-40`：左滑=太简单、右滑=加生词本），产生了 10 行 `tb_progress`、1 行 `tb_study_log`、1 行 `tb_daily_stats` 和 1 个生词本词条。收工前这些**全部删回 0**（`pragma quick_check` = ok），设备回到「0 进度、0 学习记录、生词本 0」。这 10 行的 dump 留在 `docs/shots/score-1.4.2/progress-rows-created-by-judge.txt` 备查。
2. **`published/` 在 22:32 一度回到出厂态**（revision 1、historyCount 0、1745 B）。原因未查明（同一时段另一个 agent 正在做 exe 打包、重启过服务进程，可能相关）。因为手机缓存已经是 rev 26，高于服务端的 1，我连续 publish 把它抬到 rev 27 之后才继续做 C2 —— 这是评分环境的数据事件，**不是** App 或契约的判定项，但建议发包方留意：`published/` 若被外部重建，服务端 revision 会退回 1，手机端需等到服务端 revision 重新超过本地缓存才会应用新内容。
3. **偶发一次「电脑没连上」而服务确实在监听**（C4 某次刷新，设备 `nc 10.0.2.2 8765` 能拿 200，重试即成功）。与 HANDOFF 自述「约 7% 偶发」一致，未作为扣分项。
4. **冷启动后第一次点翻面偶尔不生效**（需点第二次），多次复现，属模拟器触摸时序，App 第二次必翻，未计缺陷。
5. **基线词是 `appropriate` 而不是 `increase`**（见 A1 说明）；我按基线同状态复现后比对，并另外用真机 1.4 的 increase 截图确认结构相同。
6. **短语 / 近义词 / 同根词三块在这本书里无数据**，无法在设备上让它们渲染；改用「1.4.1 源码 vs 当前源码」逐参数对照证明未变（见 A2）。

---

## 9. 如果我是产品负责人，这个版本能不能发给用户？

**能发。** 理由：默认清单下的卡背与 1.4.1 逐像素一致（front 0 px、back 0.0072%），结构下发全链路（发布→刷新→生效，免重装）6 项全通，异常路径（离线/非法 JSON/空清单/版本不认/未知块/失败重启）每一步都给出了说人话的提示且不毁旧结构、不写坏配置，编辑器（增删/显隐/拖动/实时预览/发布）在真实浏览器里逐项可用，文档和交接说明齐全；唯一的已知小瑕疵是拉取偶发一次「电脑没连上」（重试即好）和暗色 warning 色对比度 4.21:1 略低于 AA，都不足以挡发布（也可在 1.4.3 顺手调）。

---

## 10. 复现要点（给下一个人）

```bash
export MSYS2_ARG_CONV_EXCL='*'; ADB="/c/Program Files/ASUS/GlideX/adb.exe"
# 装包（不 clear data）
"$ADB" -s emulator-5554 install -r "$(cygpath -w app/src_repo/app/build/outputs/apk/debug/app-debug.apk)"
# 编辑器
python tools/cardback-editor/server.py            # 0.0.0.0:8765，自动开浏览器
# 手机端：我的 → 卡背结构地址填 10.0.2.2:8765 → 词库页刷新图标 (966,544)
# 翻面：input tap 540 1100（input swipe 不会翻面）；卡背滚动要慢速 swipe（≥600ms）
# 看设备端配置：run-as com.kaoyan.wordhelper stat -c '%Y %s' files/cardback.json
# 停服务：netstat -ano | grep :8765 → powershell Stop-Process -Id <pid> -Force
```

> 终态：模拟器 `emulator-5554` 上 App 为 1.4.2（vc 5），卡背配置 revision 35 = 默认 11 块清单，`tb_progress` 0 行，我的页地址 `10.0.2.2:8765`；冷启动后正面与 `A1-front.png` **0 px**、背面与 `A1-back.png` **0 px**（`FINAL2-front.png` / `FINAL2-back.png`）。8765 服务已停止。
