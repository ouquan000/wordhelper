# 1.4.3.1 独立评审报告（评分 agent）

- 评审对象：工作区未提交改动（HEAD = `8016705`，1.4.2 封版），`versionCode 7 / versionName 1.4.3.1`，DB 15
- 评审方式：**只读**。未修改源码、未编译、未安装、未重启任何服务
- 证据来源：工作区源码与 diff、`git status`、构建产物 `dist/english-app-1.4.3.1.apk`（22:24，md5 与 spec 一致）、上一轮 `docs/test-report-1.4.3.md`、`docs/spec-1.4.3.md` / `docs/spec-1.4.3.1.md`
- 说明：本报告不因「未提交 git / 未发 release / 未装真机」扣分（用户明确边界）

## 0. 结论速览

| 维度 | 权重 | 本次 | 上一轮 | 变化 |
|---|---|---|---|---|
| 需求完成度 | 40 | **37.5** | 36.0 | +1.5（条进卡背、只在背面、进度条、记录页全部落地；扣卡背正文被挤，见 R3/I1） |
| 正确性与健壮性 | 25 | **19.5** | 21.0 | −1.5（两条新的「静默失效」路径，见 R1/R2） |
| 代码卫生 | 20 | **18.0** | 17.5 | +0.5（注释质量、无调试残留；仍有断行与未用形参） |
| 安全 | 10 | **9.0** | 8.5 | +0.5（暴露面未变，但已写进 spec 文档） |
| 文件/目录卫生 | 5 | **4.0** | 3.5 | +0.5（.gitignore 补齐；spec 与代码口径仍矛盾） |
| **合计** | 100 | **88.0** | 86.5 | **+1.5** |

**能不能装真机灰度：能。未发现阻断项。** 六条需求全部落地且有代码级证据；构建产物晚于全部源码改动，说明至少一次完整构建通过。但有 3 条重要问题需要知会：R1/R2 是「会静默失效」的同步路径（建议并入装前最小修复集，约 10 行）；R3 是本次唯一被设备实测确认的体验回归——重写条挤掉了卡背正文（见 §六）。

---

## 一、需求完成度（40 分 → 37.5）

| # | 需求 | 证据 | 判定 |
|---|---|---|---|
| 1 | 重写条进卡背最下面（贴底、不随内容滚） | `LearningScreen.kt:617` 以 `backActions` 槽传入；`:1860-1874` 在 `weight(1f)` 的卡背内容之后渲染 `Spacer(6dp)+Box`，且用 `clickable(indication=null){}` 吞掉空白点击防止误翻面 | ✅ |
| 2 | 只在背面显示、正面不占高度 | `:1861 if (!showFront && bottomActions != null)`；`showFront` 由 `:1689 val showFront = rotation <= 90f` 推导，翻面动画中途即切换，正面完全不进组合 → 不占高度 | ✅ |
| 3 | 真实进度条（不是猜） | 服务端 `store.py:334-345` `status()` 每条 item 带 `stage: stage_of(item)`；`store.py:77-84` 阶段机 `queued/generate/polish/judge/done/failed`（旧数据 `_STATE_STAGE` 兜底）；手机 `RewriteRepository.kt:243-267 statusOf()` 读 `/rewrite/status`，`LearningViewModel.kt:530+ fractionOfStage()` 映射 8/45/75/92/100%，`RewriteBar.kt` `ProgressRow` 渲染，4 秒轮询只在卡背可见且标记未完成时开（`LearningScreen.kt:225-232` + `LifecycleStartEffect`） | ✅ |
| 4 | 翻到背面就同步（8 秒节流） | `LearningScreen.kt:608-613` `onFlipChanged` → `syncRewriteOnBackFlip()`；`LearningViewModel.kt:434-440` 用 `lastBackFlipSyncAt` 做 8s 节流 | ✅ |
| 5 | 终态原地换文（不翻面也换） | `LearningViewModel.kt:518-524`：终态且 `(pendingTerminalSync \|\| markStillOpen)` → `syncRewriteNow()` 再读 `observeMark().first()` 重设 `pendingTerminalSync`（注释点明「同词连写第二遍」场景）；`LearningScreen.kt:1818 key(word.approach)` 让卡背内容随文本重建；`:177-182` 闪 2 秒「已更新」 | ✅ |
| 6 | 电脑端重写记录页 | `tools/rewrite-service/rewrite.html`（391 行：表头 word/model/stage/state/time/明细/操作，5s 自动刷新、暂停、今日 done/failed、错误 banner、失败行「重试」按钮 `:337-346` 带 `clientId` 精确重试）；路由 `service.py` 的 `GET /rewrite`、`GET /rewrite/records?limit&offset`；卡背编辑器首页入口 `tools/cardback-editor/index.html:72` | ✅ |
| 附 | 删 EF 系数因子 | `LearningScreen.kt:2016 CardBackBlockType.EF_STATS -> Unit`；`CardBackDefaults` 默认清单去项；已发布清单 `tools/cardback-editor/published/cardback.json` 实测 `ef-stats` 出现 **0** 次；`CardBack.kt:28` 保留枚举仅为兼容旧远端配置（README 已说明） | ✅ |

扣分点（−2.5）：
- **R3 / I1（−1.0）卡背正文被重写条挤掉**：卡片是固定高度，重写块占 353px + 50px 间隔，卡背内容可视区从整张卡降到 651px，「记忆思路」只露出 ① 的前 ~3 行，②③ 默认不可见且无「可滚」提示（`docs/test-report-1.4.3.1.md` §3 的 uiautomator 坐标 + 截图）。这是「条进卡背」这个需求自带的代价，但交付形态上属于核心产出被折叠，扣 1 分。
- 进度条文案与动效语义不一致：`fraction == null && offline` 时仍渲染转圈的 `LinearProgressIndicator`，文案却是「等电脑上线」（`RewriteBar.kt` `ProgressRow`）。
- DONE 之后百分比靠最后一次轮询残留，若用户切走再回来，进度区可能停在旧值（可接受，但属于「假信息」）。
- 卡背输入框草稿只 `remember(wordId)`（`RewriteBar.kt:69-70`）：**写了指令后翻到正面再翻回来，输入被清空**（条只在背面渲染，离开组合即丢 state）。比上一轮记录的「切 tab 丢草稿」更容易踩。

## 二、正确性与健壮性（25 分 → 19.5）

做得好的部分（确认有效）：
- 状态机与幂等：`store.py:229-261 finish_ok/finish_fail` 双守卫 `superseded`（被同词新标记覆盖时丢弃结果而不是写脏），`_enqueue` pending→删旧、running→标 superseded，`add_marks` 同 `clientId` 幂等去重；`store.py:359-366 results_page` 用 `seq > since` 取页 + `nextSince`。
- 认领守卫（B4 已修）：`RewriteRepository.kt:289-299` 带 clientId 时必须 `^w(\d+)-(\d+)$` 且 owner == wordId，队列里有新标记时旧结果直接丢。
- 迁移：`AppDatabase.kt:657-690` 加 3 列 + 新表 + `index_tb_rewrite_queue_word_id`，与 `RewriteQueueItem`（`tb_rewrite_queue`，`Index(["word_id"], unique=true)`）列名/可空/类型逐项对得上，索引名符合 Room 生成规则。
- 重组/生命周期：轮询随卡背可见性起停，`ON_STOP` 停；`imePadding` 避免键盘遮住条。
- 种子不被重写冲掉：`WordRepository.kt:1037-1040` 跳过 `source='user'`。

问题见 §六（R1–R3 重要，r1–r6 次要）。

## 三、代码卫生（20 分 → 18）

- 命名与注释整体优秀：`RewriteRepository.kt` 每个非显然决策都写了「为什么」（为什么只发一次请求、为什么 30 分钟挂回待发、为什么未知词不推进 since）；`pipeline.py:29-31` 把「输出形状只由 prompts/generate.txt 定义」写成注释，删掉了重复的 `OUTPUT_SHAPE`。
- 无调试残留：对 `RewriteBar.kt` / `RewriteRepository.kt` / `LearningViewModel.kt` grep `println(|Log.*(|TODO(|FIXME` 全部 0 命中。
- 扣分：
  - `RewriteRepository.kt:173` 语句被压成一行：`if (pending.isNotEmpty()) {            val accepted = upload(base, pending)`（上一轮已记，未修，典型的坏合并残留）。
  - `pipeline.judge(item, draft, approach)` 的 `draft` 形参仍未使用（上一轮已记）。
  - `LearningViewModel.kt:453` `if (_rewriteModels.value === RewriteModels.DEFAULT)` 用引用相等判断「还没成功拉过列表」，语义脆（当前无实际后果）。
  - `LearningScreen.kt:1818` 注释断言「AnimatedContent contentKey 是 word.id，同词内换文不会重建、光靠重组偶尔读不到新文本」——`contentKey` 只影响动画身份，`targetState` 变化仍会重组；这句诊断没有证据支撑。`key()` 本身无害，但注释会误导后来人。
  - `fractionOfStage` 与 `fractionOfState` 两套映射存在重复（可接受）。

## 四、安全（10 分 → 9）

- 未变：`tools/cardback-editor/server.py:38 HOST="0.0.0.0"`，无任何鉴权；同网段可 `GET /rewrite/results` 读全部改写结果、`POST /rewrite/marks` 替任意词下单、`POST /rewrite/retry` 反复触发付费模型。
- clientId 可伪造：守卫只校验 `owner == wordId`（`RewriteRepository.kt:294-298`），wordId 是自增整数 → 攻击者构造 `w123-1` 即可让手机接受一条伪造思路（覆盖卡背文本）。
- 本轮改进：`docs/spec-1.4.3.1.md:38` 把「0.0.0.0 无鉴权、只应在家用内网、不要暴露公网」写进已知限制；`server.py` 主路径有 `MAX_BODY=4MB` 兜底。
- 扣 1 分理由：文档不是缓解，暴露面本身未收敛（本轮也没有收敛的计划），只是「知情接受」。

## 五、文件/目录卫生（5 分 → 4）

- 已处理：`.gitignore` 增补 `tools/rewrite-service/__pycache__/` 与 `tools/rewrite-service/data/`（`:43-44`），运行时 `data/queue.json`、`state.json` 不会误入版本库；`published/` 已忽略；APK 落在 `dist/`。
- 扣分：
  - `docs/spec-1.4.3.md:184` 写「同步只放在学习页的 resume，**不在 `Application.onCreate` 做**（会和加载词抢跑）」，而 `KaoyanWordApp.kt:109-113` 就在 `Application.onCreate` 里同步，`README.md` 1.4.3 段也这么写 —— 三处口径互相矛盾（上一轮 §七 第 4 条未修）。
  - 仓库根 `.tmpdb/` 未跟踪也未忽略。
  - README 写 grok「单条约 10 分钟」，spec 写「5–8 分钟」（在用户给的 5–10 分钟带内，仅口径不一）。
  - 时序提示（不计分）：`docs/test-report-1.4.3.1.md` 于 22:30 才落盘，评审开始时 `spec-1.4.3.1.md:30` 指向的是一个尚不存在的文件；现在指向已成立。

---

## 六、问题清单

### 阻断（0 条）

无。

### 重要（3 条）

**R1. 一个「手机上不存在的词」会让结果同步永久停摆，且完全静默**
- 位置：`RewriteRepository.kt:194-205`（`unknownWord` → `return@withLock` 且 error=null，不推进 since）。
- 推理：`results_page` 按 `seq > since` 取页，只要某个 result 的 word 在 `wordDao.getWordIdByKey()` 里查不到，本页就整页重来。**偶发场景**（换机恢复时种子没导完）是文档写明的、可自愈；但**如果这个词永久不存在**（手机删过词/装的书不同/电脑端记录页对任意词重试、或局域网内有人 POST 一条乱词），同一页永远包含它 → `since` 永不推进、每次 sync 都提前返回、后续所有词的改写再也拉不回来，且无日志、无 UI 提示。
- 复现（不动手机）：`curl -X POST http://<电脑>:8765/rewrite/marks -d '{"marks":[{"clientId":"w999999-1","word":"zzzz","meaning":"x","model":"ds-v41-flash"}]}'` → 电脑端会跑完并落一条 results（seq 递增）→ 手机之后每次 sync 都在这一条上短路（若该词不在词表）。注意：这是**绕过 clientId 守卫**的路径——`applyResult` 里查不到词就返回 null，守卫代码根本没机会执行。
- 建议修法（约 5 行）：给「未知词」设一次性重试计数（`since → 记住 seq，最多重试 1 次，仍失败则跳过并推进 since + 记日志`），不要无限期卡在同一页。

**R2. 电脑端 `data/` 被清空后，客户端 `since` 永远高于服务端 `seq`，结果再也拉不到**
- 位置：客户端 `RewriteRepository.kt:184` 只写不读 `rewrite_last_seq`；服务端 `store.py:97 self.seq = 0`、`:361 entries = [... if e["seq"] > since]`。
- 推理：`data/state.json` 丢失/手动清空 → seq 从 1 重新计数 → 手机 `since`（比如 40）永远大于新 seq → `/rewrite/results` 永远返回空页，且**没有任何自愈路径**。
- 反证当前注释：`RewriteQueueDao.kt:27` 与 `RewriteRepository.kt:166` 都写「电脑端数据被清掉时靠它自愈」——实际只自愈了**队列**（30 分钟把 queued 挂回 pending 重发），自愈后的结果 seq 依然低于 `since`，仍旧拿不到。注释给了错误的安全感。
- 建议修法（约 5 行）：`status()` 已经返回 `seq`（`store.py:355`），客户端在 sync 时若发现 `serverSeq < since` 就把 `since` 归零重拉一次。

**R3. 重写条挤占卡背正文：核心的三行推导默认只露出 ① 的前 3 行（设备实测）**
- 证据：`docs/test-report-1.4.3.1.md` §3 的 uiautomator 坐标树——卡片固定在 `[44,378][1036,1735]`，卡背内容滚动区只有 `[110,626][970,1277]`（可视高 651px），「记忆思路」正文 `[138,1151][942,1277]`（可视高仅 126px ≈ 3 行，在「意思是"自己」处硬切，②③ 完全不可见、无渐隐/展开提示），重写块占 `[99,1327][981,1680]`（353px）+ 50px 间隔。
- 代码侧对成因的判断成立：`LearningScreen.kt:1762-1766` 内容区是 `weight(1f)` 的 `Box`，`:1860-1874` 的重写条在它**之后**才渲染，所以这 353px 只能从内容区里扣；卡片高度固定，内容只能靠滚动补。
- 定级：**重要（不是阻断）**——内容滚动区在 dump 里标了 `SCROLL`（`CardBackBlocks(..., scrollState = backContentScrollState)`），②③ 理论可滚到。**但这个结论本轮没有被验证**：测试 agent 在做 swipe 时模拟器被关（报告 §三 I1 已明确标注「能滚→降级次要；不能滚→升阻断」）。装真机后请第一件事就是「背面向下滚一屏，确认 ②③ 能完整读」。
- 建议：空闲态把条压到一行（按钮 + 模型），输入框与状态行按需展开——353px 里真正常驻的只有 ~110px，压完能给正文多出 ~290px。

### 次要（6 条）

- **r1. 终态写回与「新标记」的竞态**：`applyResult` 在守卫（`:289-299`）通过后，`:335` 无条件 `queueDao.updateStatus(wordId, DONE/FAILED, …)` 按 `wordId` 更新。若用户在读守卫与写回之间重新打了标记，新标记会被误置 DONE（且 `getPending()` 只取 pending，新指令永不上传，按钮显示「上次重写 …」）。窗口是毫秒级，概率低，但属真实缺陷。修法：`updateStatus` 带上 `WHERE client_id/created_at = 捕获值`。
- **r2. 「取消」只在本地生效**：PENDING 态再点 = `cancelRewrite()` 删本地队列行（`RewriteRepository.kt:137` 附近），但若上传请求其实已经到达电脑（超时/丢包场景），服务端仍会跑完并把结果写回——`applyResult` 对 `mark == null` 且 clientId 合法的结果**直接接受**（注释写明这是换机恢复的设计）。结果：用户以为取消了，5–10 分钟后文字照样被改。
- **r3. 时钟依赖**：`statusOf` 用**手机**的 `mark.createdAt` 去比**电脑**的 `updatedAt`（`RewriteRepository.kt:250-256`，容差 `TERMINAL_STALE_TOLERANCE_MS = 2s`）。两端时钟差 > 2 秒时，刚跑完的终态会被当成「旧终态」滤掉 → 进度退化为「等电脑上线」，要等下一次翻面 sync 才换文。另外 `?: Long.MAX_VALUE` 再 `+2000` 会溢出成负数（`:255`），解析不了时间的终态会被无条件滤掉。
- **r4. 非评分类失败不重试**：`pipeline.py` 里 `normalize()` 不合规（不是恰好 3 行）或生成返回非 JSON 时直接抛 `PipelineError`，**不进第二次 attempt**（`MAX_ATTEMPTS=2` 只覆盖「评分不过」）。一个格式抖动就整条 failed，用户要手动重试。
- **r5. PENDING 判定残留**：`enqueueAndSync` 用「sync 后队列行的 status 是否还是 QUEUED」判断是否提示「已记下，等电脑连上再发」；若同一次 sync 已把结果拉回并置 DONE，会误报一次「等电脑连上」（文案与事实相反）。
- **r6. 文档口径矛盾**（同 §五）：`spec-1.4.3.md:184` ↔ `KaoyanWordApp.kt:109-113`（`test-report-1.4.3.1.md` 已于 22:30 落盘，原「指向不存在文件」一条随之消解）。

### 观察（不计分，供参考）

- 模型下拉 5 项，但上一轮实测只有 `ds-v41-flash` 稳定可用（`docs/test-report-1.4.3.md` D1：step5 两轮评审 0 分、glm53/gpt 判不过、grok 三次 502）。本轮 `providers.py:27-29, :242-247` 加了 grok 进程内串行锁，spec 说明「串行后可跑但 5–10 分钟/条」——**没有独立证据表明其余 4 个模型在 1.4.3.1 上已能通过评审**。灰度期间建议默认 `ds-v41-flash`，或在 `/models` 里只下发已验证可用的项。
- 服务端 `_prune` 只保留最近 500 条终态（`store.py` KEEP_FINISHED），记录页看不到更早的任务，但结果文件仍在，属于设计取舍。
- 模拟器冒烟（`spec-1.4.3.1.md:28`，rev 9 原地换文）是作者自述，本报告未独立复现。

## 七、上一轮历史问题复核

| 编号 | 问题 | 本轮状态 | 证据 |
|---|---|---|---|
| A1 | 上传缺词头字段 | **已修** | `RewriteRepository.kt:347-353` 带 `phonetic / pos(posOf(meaning)) / meaning / currentApproach` |
| B1 | `_prune` 缺失 | **已修** | `store.py` `_prune()`：保留 pending/running + 最近 500 终态 |
| B2 | 评委看得到生成过程 | **已修** | `pipeline.judge()` 只喂 `_brief(item).split("旧版思路")[0]` + 成品三行 |
| B3 | QUEUED 卡死 | **已修（有残余）** | `RewriteRepository.kt:167-170` 30 分钟挂回 pending 重发；但 seq 重置场景未自愈 → 见 R2 |
| B4 | clientId 守卫 | **已修** | `:289-299` 正则 + owner 校验；`rewrite.html:338` 重试也带 clientId 精确命中 |
| B5 | 缺维度按 0 | **已修** | `pipeline.judge()` `setdefault(dim, 0)` |
| D2 | 评委 token 截断 | **已修** | 评审 `max_tokens=8192` + 首个 JSON 解析失败重试一次（上轮 3000 截断） |
| D3 | 重写条出现在正面 | **已修** | 条挪进 `backActions`，`LearningScreen.kt:1861` 仅背面渲染 |
| 新-1 | 生成输出形状重复定义 | **已修** | 删 `OUTPUT_SHAPE`，`pipeline.py:29-31` 注释说明单一来源 |
| 新-6 | `=== RewriteModels.DEFAULT` 引用比较 | **未修** | `LearningViewModel.kt:453`（无实际后果） |
| 旧-§四 | 空 clientId + 有标记仍接受 | **未收紧** | `applyResult` 分支保留（当前无注入面，仍是隐患） |
| 旧-§七-4 | 文档与代码的同步口径矛盾 | **未修** | 见 r6 |

## 八、已知限制文档核对（用户点名两条）

| 限制 | 是否写进文档 | 位置 |
|---|---|---|
| grok 串行后约 5–10 分钟/条 | ✅ | `spec-1.4.3.1.md:35`（5–10 分钟）；`spec-1.4.3.md:155/228`（5–8 分钟）；`README.md:248`（约 10 分钟） |
| 换机恢复首次启动可能截断、不推进 since、下次重试 | ✅ | `spec-1.4.3.1.md:36`；`spec-1.4.3.md:230`（含「最多重试 3 次」，与 `httpGet` 默认 attempts=3 一致）；代码侧 `RewriteRepository.kt:194-205` 行为吻合 |
| 0.0.0.0 无鉴权 | ✅（本轮新增） | `spec-1.4.3.1.md:38` |

结论：用户点名的两条限制**都有文档**，措辞与代码行为一致（R1 的「永久未知词」是这条限制的未覆盖子情形，文档只覆盖了「临时未导入」）。

## 九、交付判定

**可以交付给用户装真机灰度：是。**

支持理由：
1. 六条需求全部落地，且不是「声明完成」——调用链（服务端 stage → 手机 statusOf → 轮询 → 终态 sync → 卡背换文）逐段有代码证据。
2. 无阻断问题；DB 从 1.4.3 → 1.4.3.1 **不涉及迁移**（同为 15），本次升级最脆弱的一环不存在。
3. 构建产物 `dist/english-app-1.4.3.1.apk`（22:24）晚于全部源码 mtime（最晚 22:18），说明这次改动至少完整构建通过一次；md5 `f3cf884f…` 与 `spec-1.4.3.1.md:44` 记录一致。
4. 独立测试报告（`docs/test-report-1.4.3.1.md`，22:30）在模拟器窗口内跑了 1/2/3/4/10 五条：**5 PASS、0 FAIL、无崩溃/ANR**；第 5–8 条（重写/撤销/模型下拉/输入框）因模拟器被关未验证——这部分依赖 1.4.3 轮的实机结论（rev 递增、撤销回退）+ 本报告的代码链证据。

装前最小修复集（建议，非硬门槛；R1+R2 合计约 10 行，不动 UI）：
1. **R1**：未知词最多重试 1 次后跳过并推进 `since`（+ 一行日志）。
2. **R2**：sync 时用 `status().seq` 与本地 `since` 比较，`serverSeq < since` 则 `since = 0` 重拉。
3. **r6**：把 `spec-1.4.3.md:184` 改成与 `KaoyanWordApp.kt:109-113` 一致（`test-report-1.4.3.1.md` 已落盘，原「引用不存在文件」一条自动消解）。

可选（本轮不改也能装，但灰度第一件事请验证）：
4. **R3**：装真机后先翻到背面**向下滚一屏**，确认 ②③ 能完整读到。能滚 → 记作体验债；不能滚（翻面手势吃掉纵向滑动）→ **升级为阻断**，必须先把条压成一行再发。
5. **R3 的止血版**：空闲态把条压成一行（按钮 + 模型），输入框/状态行按需展开，见 §六 R3。

如果 R1/R2 这版不做：也能装，但请在灰度期间盯两个症状——「某个词点了重写、进度一直停在排队/不动、其它词却正常」（R1），以及「电脑端清过 data 或重装服务后，手机再也收不到新改写」（R2）。两者都表现为「静默不生效」，用户很难自己说清楚。

## 十、评分明细

```
需求完成度   37.5 / 40   （六条全落地；扣 R3 卡背正文被挤，以及进度文案/残留值/翻面丢草稿）
正确性与健壮性 19.5 / 25   （守卫/状态机/迁移扎实；扣 R1 R2 两条静默失效 + r1-r5）
代码卫生     18.0 / 20   （注释解释「为什么」做得好、无调试残留；扣断行、未用形参、引用比较、误导性注释）
安全         9.0 / 10   （暴露面未收敛，已文档化；clientId 可伪造）
文件/目录卫生  4.0 / 5    （.gitignore 已补；spec:184 与代码矛盾、.tmpdb 未忽略）
────────────────────────
合计         88.0 / 100

### 评审局限（必须声明）

- 未编译、未安装、未连模拟器：编译正确性由「构建产物晚于源码」间接推断，不是直接验证。
- 未独立跑通 5 个模型；模型可用性引用 `docs/test-report-1.4.3.md` D1 与本次服务端配置，未复测。
- 已读 22:30 落盘的 `docs/test-report-1.4.3.1.md`：1/2/3/4/10 五条 PASS，第 5–8 条未验证（模拟器被关）。**「卡背滚一下能否看到 ②③」这条关键待验证项，本报告给不出结论**——它直接决定 R3 是「重要」还是「阻断」。
- 迁移未在真机验证（本版不涉及迁移；若用户手机当前是 1.4.2/versionCode 5，首次启动会跑 `MIGRATION_14_15`，SQL 与实体逐列一致，但 1.4.3 测试报告未记录迁移结果，建议装前确认手机的 `versionCode`）。
