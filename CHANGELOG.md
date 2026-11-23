# 更新日志

本文件记录「不爱背单词」（wordhelper）的版本变更。日期为北京时间。

## v1.5.3v2（2026-11-23）— 卡背瘦身、反馈反哺大脑、模型精简到 4 档

- 卡背瘦身 + 一维直滑手势：跳过彻底收回、进度条复活。
- 词性小标补丁：行内徽章与 `→`/`——>` 后的义项分组按考纲原义贴。
- 反馈反哺大脑：差评的 `reasons`/`comment` 进入历史摘要与洗词上下文，「太烂了」触发整套换招硬约束。
- 免洗金牌离线锁补漏：锁定词在闲时洗词/批量重写/词卡热更时全程受保护。
- 模型精简到 4 档：DeepSeek V4.1 Flash / V4 Pro / GLM 5.3 / MiniMax M3.1 Flash；其余全部关闭。

## v1.5.3（2026-11-23）— 四笔旧账清完，助记大脑换血

1.5.3 先把 1.5.2v2 没修透的四笔账结清，再把重写服务的「脑子」换掉：跳过按钮不再在历史 DONE 词上常驻，RewriteBar 只保留一条状态文案，记忆思路的词性小标改成真正的行内徽章；点踩可以多选原因并补一句吐槽，手改助记可以一键锁定免洗；后端新增闲时洗词 Worker、红黑榜风格规则和可配置模型/Judge 规则。

### 一、跳过按钮与状态文案

- `skipRewriteAvailability` 对「历史已完成 DONE、本轮没有 activeRequestId」返回 `NO_MARK`，彻底隐藏跳过入口；只有当前词有活跃 pending/queued，或本轮 requestId 对应的 DONE 出现时才显示。
- `RewriteRepository.markNeedsReinsert` 改为幂等，不再把 SQLite 的 0 行更新误判成「重写记录已变化」；同词可以连续无限轮「重写 → 跳过 → 回队」。
- 新增 `RewriteBarStatus` 单一状态源：PENDING 只显示「已标记 · 待上传」，QUEUED 只显示「电脑已收到 · 排队中」，DONE 显示上次重写时间，FAILED 只显示失败原因；进度条不再和状态行重复。JVM 单测覆盖状态互斥。

### 二、记忆思路词性小标返工

- 改用 `androidx.compose.foundation.text.InlineTextContent` + `appendInlineContent`，彻底废弃行首 `Row` 和拆行切段；`①②③④` 始终贴左，`→` 后的中文义项只在对应义项前嵌入同色圆角小标。
- 显式 `v.` / `n.` / `adj.` 与中文「动词 / 名词 / 形容词……」都识别，并用该词 meaning 的真实词性集合约束；`correlate`、`exotic`、`appropriate`、`extract` 均已实机验证。
- `MemoryPosTagger` 16 项单测覆盖显式 token、中文词性、箭头义项分组、宁缺毋滥。

### 三、反馈原因、手改助记与锁定

- 点「👎 没帮助」弹出快捷多选：谐音不准 / 词根太生僻 / 绕弯注水 / 不像人话 / 没扣住词义 / 标签贴歪，另有自由吐槽输入；结构化写入 `tb_feedback.reasons/comment` 并同步后端 `feedback.jsonl`。
- 卡背支持「编辑思路」直接保存，保留 `prevApproach/prevMethod`、`rev+1`、`source=USER`，不碰 `tb_progress`。
- 新增「锁定 · 免洗」开关与 `tb_memory_lock`；锁定词在词卡热更、闲时洗词、批量重写时受保护，除非用户主动解锁。
- Room 升到 26，新增无损 `MIGRATION_25_26`（`tb_feedback.reasons/comment` + `tb_memory_lock`），不删表、不碰 `tb_progress` 与 `tb_word_memory`。

### 四、助记大脑换血

- 新增 `tools/rewrite-service/config/style_rules.json`：红榜 6 个风格样本、黑榜 5 条硬规则、方法权重（sound 73.9 / root 8.2 / shape 6.5 / contrast 2.0 / meaning 9.4 / polysemy 0.0）与 Judge 扣分/打回规则；Prompt 与 Judge 已注入。
- 新增 `config/models.json`，模型列表、Judge 候选、tier/weight 从配置读取；手机端模型切换直接消费服务端 `/models`。
- 新增闲时洗词 Worker（默认关闭，独立开关 + 速率上限，只在手动队列空闲时跑，不抢 6 槽；自动筛高踩 meaning/polysemy、未锁定、未高赞的老卡，Judge 通过才进结果流）。
- 新增锁 API `/rewrite/lock`、`/rewrite/locks`、Worker 状态/开关 API，反馈支持 `reasons`/`comment`。

### 五、五组黑榜词重洗样本

`referee`、`obscure`、`monopoly`、`create`、`cathedral` 已用新 brain 重洗，全部 `status=ok`；完整前后对照见交付包 `blacklist_before_after.json` 与发版报告。典型变化：`referee` 从「热法理」拆音改为「ref—e—ree + 哨声」动作链；`monopoly` 从「摸拿不离」改为抽象解释；`create` 从 cre/ate 词根课改为「可立—诶特」画面。

### 六、验证

- Android：`testProdDebugUnitTest`、`testDevDebugUnitTest` 各 **47 套件 / 334 项 / 0 失败**。
- 后端：`tools/rewrite-service` **288 项 unittest 全过**、`test_unverified.py` 30 项全过；`tools/cardback-editor` health 31 / hardening 21 / sync_store 67 / sync_epoch 20 / cards_publish 36 / rate_limit_vip 48 全过，TLS 9 项（1 项按环境跳过）。
- 模拟器 `mf`（API34，1080×2340）实机：历史 DONE 的 `appropriate` 翻卡无「跳过」；点「再重写一次」只显示一行「电脑已收到 · 排队中」，随后真实 `adb shell input tap` 跳过，卡片立即换到下一词；`correlate` / `exotic` / `appropriate` 行内词性小标、`create` 单 `v.` 徽章均已截图。
- 版本 `versionCode 30` / `versionName 1.5.3`；prod APK 24,150,122 B（SHA-256 `0D3E4864AA84D86BE3DC3481BA9B6397A9EEBD5EAA6C966DE7B3FD22ADA877E9`），dev APK 24,150,130 B（SHA-256 `3A4C0D9470BDE313CF29035F92601F6477E902CD2E30E86174B39536CD7250D4`）；两包证书 SHA-256 均为 `963533138ed73e92e9a2859f80fdf18becb4ee5a83132c55634b965d7164ba1c`。

## v1.5.2v2（2026-11-22）— 跳过真正换卡，词性小标不再只认拉丁字母

v1.5.2 的发版验收漏了两处：二次重写后「跳过」仍可能卡住，记忆思路里的词性小标只识别正文里显式写出的 `v.` / `n.`，`correlate` 这种「动词是……名词是……」的中文描述型完全没有标。v1.5.2v2 按真实点击逐轮修，不再用合成 requestId 或单张 `increase` 截图代替验收。

### 一、跳过：去掉会话锁存，改成数据库当前行 + 本轮 requestId

- `LearningViewModel` 删除 `_skipRewriteLatchedWordId` 及所有 session 锁存；`currentRewriteSkipAvailability` 只由「当前词 + 当前书 + 当前队列行 + 本轮 requestId」推导，App 重启、进程回收、从别的页面回来都不再出现「按钮看着能点、实际弹已经在等结果」。
- `RewriteRepository.markNeedsReinsert` 改成幂等：只要队列行还在就返回成功，不再把 SQLite 的 0 行更新误判为「重写记录已变化」。
- `skipCurrentWordForRewrite` 先清掉上一轮遗留的 `pendingReinsertWords` / `restoredWaitingRewriteWords`，再把词放进等待集合并立即从会话队列前移；同一词可以无限轮「重写 → 跳过 → 回队」。
- 队列末尾/重启恢复：`syncRewriteNow` 在没有当前卡但仍有等待词时主动 `loadQueueForBook`；等待页的「现在刷新」会重新构建队列，`needs_reinsert` 的 DONE 行能插回来。

### 二、结果回收：游标回看 1 条 + 回环地址不再被身份校验误杀

- 服务端「写结果」和「抬 highWater」之间有一瞬窗口，App 可能把游标停到最新 seq 却漏掉刚写入的那条。本地还有排队行时，结果分页第一页故意回看 1 条，补上这个边界。
- `OriginTrustPolicy.needsProof` 排除 `127.0.0.1` / `localhost` / `::1`；模拟器 `adb reverse` 或本机调试在 `instance_id` 落库后不会再被 `/identity` 挑战静默拦住。
- 未配对（token 为空）时允许显式填写的明文 HTTP 私网地址作为兜底；带 token 时仍严格禁止公网明文 HTTP。

### 三、记忆思路词性小标：中文描述型也能贴

- 新增 `MemoryPosTagger`：识别显式 `v.` / `n.` / `adj.` 等 token，也识别「动词 / 名词 / 形容词 / 副词……」等中文词性词，并用该词 meaning 里真实存在的词性集合做约束，宁缺毋滥。
- `LearningScreen` 的 `WordMemorySection` 改为按 `TaggedLine` 分段渲染：有词性的行在行首放 `PosBadge`，正文里已有的显式 token 继续用同色 SpanStyle 染色；箭头 `→` / `——>` 后的义项串按 meaning 的词性顺序分组，跨词性共用义项不丢。
- 真实样本覆盖 `correlate`（v./n. 中文描述）、`extract`（v./n. 共用「摘录」）、`increase`（双词性）、`create`（清洗后单 `v.`）；`MemoryPosTaggerTest` 14 项。

### 四、验证

- Android：`testProdDebugUnitTest`、`testDevDebugUnitTest` 各 **46 套件 / 326 项 / 0 失败**（含跳过状态机、循环地址、词性解析、点赞封顶）。
- 后端：`tools/rewrite-service` 214 项 unittest + 30 项 unverified 全过；`tools/cardback-editor` 六组脚本 health 31 / hardening 21 / sync_store 67 / sync_epoch 20 / cards_publish 36 / rate_limit_vip 48 全过。
- 模拟器 `mf`（API 34，1080×2340）真实点击验收：同一词 `appropriate` 连续三轮「重写 → 跳过」，分别立刻换到 `evaporate`、`system`、`manifest`；每轮结果回来后在队列中插回，第三轮在 14/14 队列末尾由「现在刷新」插回，重启后仍能看到该卡；`create` 只显示一个 `v.` 徽章，`appropriate` 的 `adj.` 行内小标可见。
- 版本 `versionCode 29` / `versionName 1.5.2v2`；prod APK 24,117,350 B（SHA-256 `4E32DBAF2E4DDF833EA17267EBE9B76AABDE11C3933F202A73D48F1D8971C354`），dev APK 24,117,366 B（SHA-256 `235869395818600AB3576D1EEEA1208C1398D76C8E7912F9D02EBE3117A1CD40`）；两包证书 SHA-256 均为 `963533138ed73e92e9a2859f80fdf18becb4ee5a83132c55634b965d7164ba1c`。

## v1.5.2（2026-11-22）— 二次重写不再卡、点赞能连击、公网限流不误伤

这一版按体检报告里最影响使用的几处动手：二次重写后「跳过」偶尔点不动、底部「认识」绿框带到下一张、公网 429 后「立即重试」空转，以及词库释义和卡背排版的脏数据。顺手把「点赞」做成可以连击累加的解压交互。

### 一、二次及多轮重写：四道闸门全部打通

- 只有**本轮 requestId** 真的出了结果，才会把「跳过」置灰；旧一轮的 DONE 不再挡住新一轮。
- 新一轮开始时会把该词从 `sessionSkippedWordIds` 移除，同一词在一个 session 内可以无限轮「重写 → 跳过 → 回队」。
- `launchSubmit` 重入不再静默吞点击，会提示「正在处理上一步，稍等一下」；`putIfAbsent` 命中已有等待任务时提示「这张卡已经在等结果了」并刷新计数。
- 新增 `SkipRewriteAvailabilityTest` 覆盖旧 DONE 不挡新轮、仅本轮结果 READY、连续三轮 AVAILABLE。

### 二、底部「认识」绿框不再跨词残留

- `RecognitionActionPanel` 增加 `resetKey`（当前词 id），`LaunchedEffect` 和每次 `submit()` 后都把 `cursorIndex` 清回 `-1`；评分提交后不会把绿色外发光游标带到下一张卡。

### 三、公网 429：真实 IP + VIP 通道 + 可复位

- 后端限流优先读 `CF-Connecting-IP`（再 `X-Forwarded-For`，最后 peer IP），cloudflared 回源不再让所有公网设备共用一个 `127.0.0.1` 桶。
- 携带合法 `X-Cardback-Token` 的自家请求走 VIP write 桶（120 次/分钟、5000 次/天）；无 token 的公网请求维持 20 次/分钟、200 次/天。
- 新增需 token 的 `POST /admin/rate-reset`；`SERVICE_VERSION` 升到 `1.5.2`。
- Android「立即重试」先清 `SyncRepository.rateLimitedUntil` 再发同步，不再冷却期内空转；「我的」与「同步状态」页把模糊的待重写数拆成「待上传 X 条 · 电脑处理中 Y 条」。

### 四、词库脏释义清洗

- 清洗 9 个同一词性重复前缀的词（`create`、`bishop`、`agitate`、`society`、`conduct`、`yield`、`property`、`spare`、`cast`），并把同词性内部完全重复的义项保守去重；跨词性共用义项（如 `extract` 的 `v. 摘录` / `n. 摘录`）保留。
- 新增 `MeaningTextFormatter`，渲染层同时做合并同类词性、同词性内义项去重兜底；`create` 现在只显示一行 `v.`。
- 两份 `cet6_cards.json`（`content/seed` 与 App `assets`）同步为 v3 / 5228 词，语义一致。

### 五、卡背「记忆思路」行内词性小标

- 记忆思路正文里本来就写出的 `v.` / `n.` / `adj.` 等前缀，改为同色行内小标（`AnnotatedString` + `SpanStyle`，10sp、半透明底色），不猜、不乱贴；没有词性标记的正文保持原样，不撑行距。

### 六、点赞 ++ 连击

- 卡背「👍 有帮助」支持连续点击累加，按钮实时显示当前词累计赞数（如 `👍 5`），每次点击有缩放弹跳 + 轻震动。
- 连续点击只在本地累加，停手 1.8 秒或切走卡片时合并成**一条**反馈写入 `tb_feedback`（`count` 字段）并走原批量同步队列；不会每点一次发一次请求。
- Room 升到 **25**，新增无损 `MIGRATION_24_25`（`tb_feedback.count INTEGER NOT NULL DEFAULT 1`）；不删任何表、不碰 `tb_progress` / `tb_word_memory`。
- 后端 `POST /rewrite/feedback` 兼容可选 `count`（1..50），`profile` 权重按 `1 + min(3, log2(count))` 平滑，狂点不会把统计模型冲崩。

### 七、验证

- 后端：`tools/rewrite-service` 214 项 unittest 全过、`test_unverified.py` 30 项全过；`tools/cardback-editor` 六组脚本全过（health 31 / hardening 21 / sync_store 67 / sync_epoch 20 / cards_publish 36 / rate_limit_vip 48）。
- Android：`testProdDebugUnitTest`、`testDevDebugUnitTest` 各 **311 项，0 失败**（新增跳过三轮、释义去重、词性解析、点赞封顶等用例）。
- 模拟器 `mf`（API 34，1080×2340）实测：`increase` 卡背双词性徽章正常、连点三次显示 `👍 3` 并弹「已记录：这条思路有帮助 +3」、重写后状态只显示「已标记 · 待上传（等电脑接收）」、点「跳过」进入下一张、评分后底部无绿框残留。
- 版本 `versionCode 28` / `versionName 1.5.2`；prod APK 24,117,354 B（SHA-256 `54E00190363A6DE04B78D01FEBE57F4BB3A5BC65DD2A6D4BFDAEA4E317138DDD`），dev APK 24,117,362 B（SHA-256 `710BEEB236937B3848C1763F43611009FE13A1875628CC75CAA5360EFA6960E6`）；两包证书 SHA-256 均为 `963533138ed73e92e9a2859f80fdf18becb4ee5a83132c55634b965d7164ba1c`。

## v1.5.1（2026-11-22）— 重写按钮不再装死，换网络也能自己找路

这一版主要修「点重写思路没反应」：不是按钮坏了，是后台同步把本地排队写库的锁占住了。模拟器复现时局域网秒回，所以看起来正常；真机上校园网 IP 一变、公网超时，后台同步要拖几十秒，点击就一直停在「重写思路」，既不落库也不提示。

### 一、重写思路：先落库，再联网

- `RewriteRepository.enqueueAndSync()` 不再先抢 `syncMutex`：本地 `tb_rewrite_queue` 写入只拿 `queueWriteMutex`，写完才去同步。后台同步再慢，也不会挡住点击落库。
- 点击后立刻显示「已标记，正在发送…」，发出去显示「已发给电脑，等它重写」，没发出去显示「已记下，等电脑上线后自动重写」——不再出现「点了完全没动静」。
- 落库失败、同步异常都有中文兜底提示，不会静默吞掉。

### 二、默认公网地址：本地注入，不进仓库

- `build.gradle.kts` 支持从本地未入库的 `keystore.properties` 读 `defaultRemoteUrl`，生成 `BuildConfig.DEFAULT_REMOTE_URL`；仓库默认值永远是空串，私人域名不写进任何 Git 跟踪文件。
- `SettingsRepository` 在本地没存过公网地址时自动用这个默认值预填；用户手动清空过就尊重用户，不再硬塞回去。
- 已经写进历史文档/注释里的私人域名统一脱敏成 `example.com` / `your-tunnel.trycloudflare.com`。

### 三、三条链路都走「局域网优先，公网兜底」

- 词卡热更（`WordbookUpdateRepository`）、学习记录同步（`SyncRepository`）、重写思路（`RewriteRepository`）都复用同一套候选地址：先试局域网，约 2 秒连不上自动切公网地址（带 `X-Cardback-Token`）。
- 局域网请求「快失败」、公网请求给足超时；离线时仍然本地排队，不丢数据。

### 四、1.5.0 遗留的三个小毛病

- **冷启动状态失忆**：`WordbookUpdateRepository` 初始化时从设置回读上次同步的 `sha256` / 时间 / 词数，杀后台重开不再显示「还没同步过」。
- **热更报错排版**：个人页词卡状态挪到按钮下方整行展示，英文超时异常翻译成「局域网连不上，且未配置公网地址……」这类人话，长错误压成一行。
- **自动备份状态不刷新**：个人页在启动后最多轮询 40 秒，首次备份一完成就刷新出时间，不再卡在 2.5 秒的旧值。

### 五、验证

- 后端：`tools/rewrite-service` 197 项 unittest 全过；`test_unverified.py` 30 项全过；`tools/cardback-editor` 六个脚本全过（health 31 / hardening 20 / sync_store 67 / sync_epoch 20 / cards_publish 36 / TLS 9，TLS 1 项按环境跳过）。
- Android：`testProdDebugUnitTest`、`testDevDebugUnitTest` 各 293 项，0 失败。
- 模拟器 `mf`（API 34，1080×2340）跑通五项冒烟：冷启动进学习页、翻卡点重写有即时反馈、背词评分切词、自动备份落盘、词卡热更 + 强杀重开状态保留；脚本沉淀在 `scripts/emulator_smoke.ps1`。
- 版本 `versionCode 27` / `versionName 1.5.1`；Room 仍为 **24**，无 schema 变更、无破坏性迁移；`tb_progress` / `tb_word_memory` 未做物理删除。

## v1.5.0（2026-11-22）— 不用手动备份，词卡也能热更

这一版解决两个日常痛点：重装/卸载后数据容易丢，以及每次改词卡都要重新装 APK。

### 一、零配置「抗卸载自动备份」

- 启动后后台静默备份一次，之后每 **12 小时**最多一次，全程 `Dispatchers.IO`，不阻塞界面、不弹权限框。
- Android 10+ 用 `MediaStore.Downloads` 写入公共目录 `Download/WordHelperBackups/kaoyan_words_YYYYMMDD_HHmm.db`；卸载 App 后系统不会删这个目录。
- 自动滚动清理：只删本 App 创建的旧备份，最多保留最近 **7 份**。
- Android 8/9（API 26–28）静默回退到 App 外部目录，功能可用且不崩溃（该目录卸载时会随 App 删除，这是系统限制）。
- 个人页备份区新增一行状态：「自动备份已开启 · 存至 下载/WordHelperBackups · 上次：…」；原有手动导出/恢复按钮保留，重装后直接选这个目录里的 `.db` 就能恢复。

### 二、词卡 JSON 热更新（免装 APK）

- 电脑端 `tools/cardback-editor/server.py` 新增安全合并发布：以 v3 种子为基底，把 `tools/rewrite-service/data/results/*.json` 中 `status == "ok"` 且 `unverified == false` 的 `approach` / `method` 合并进 `published/cards/cet6_cards.json`，并生成 `manifest.json`（version / book / count / sha256 / updatedAt），原子写入并保留 `.bak`。
- 新增接口：`GET /cards/manifest.json`、`GET /cards/cet6_cards.json`（均 `Cache-Control: no-store`）、`POST /cards/publish`；全部走 `X-Cardback-Token` 鉴权。`/health` 的 `capabilities` 增加 `cards_ota`。
- 顺带把 `do_POST` 改成**先鉴权、再读 body**，未认证的大包请求不会再被读进内存。
- Android 新增 `WordbookUpdateRepository`：支持「局域网 + 公网」双通道，设置里可填一个可选的公网地址（如 Cloudflare Tunnel）；点同步时先以约 2 秒超时试局域网，连不上自动切公网。
- 拉取流程：manifest → 比对本地已应用的 `sha256`（一致则提示已是最新）→ 下载词卡 → 校验 SHA-256 与 `book == "六级记忆卡"` → 原子写入 `filesDir/cet6_cards.json`（失败保留 `.bak`）→ Room 事务刷新词义/例句与助记。
- 数据安全：只更新 `source != "user"`、`rev == 0`、无撤销记录、未标 `unverified` 的助记；用户手改过的助记一律保留。**全程不碰 `tb_progress`**。
- 个人页新增「同步最新词卡」按钮与状态行（上次同步时间 / 更新词数），设置页新增「词卡公网同步地址（可选）」。

### 三、同步契约补齐 FSRS 4 字段

- `SyncRepository.kt` 与 `tools/cardback-editor/sync_store.py` 以**可选字段**方式补齐 `fsrs_stability`、`fsrs_difficulty`、`fsrs_lapses`、`fsrs_state`。
- 旧手机/旧数据缺这 4 个键不会报错、不会被拒；服务端只对「带了但类型不对」的字段拒收（`fsrs_lapses` 必须是整数，布尔值也拒）。
- 服务端新增基础字段类型校验，防止脏类型污染同步数据。

### 四、词库基线对齐 v3

- `content/seed/cet6_cards.json` 从 v2 对齐到 **v3**：`version=3`、`count=5228`，与 `app/src_repo/app/src/main/assets/cet6_cards.json` 语义一致；17 个词条的记忆思路以 v3 为准。
- 旧的 `_recon/word-audit/root-rewrite/apply.py` 已标记废弃并加硬闸门：默认拒绝运行，带 `--force-legacy-v2` 时若检测到种子已是 v3 也会退出，避免再把 v3 覆盖回 v2。

### 五、兼容与验证

- Room 数据库版本保持 **24**，无 schema 变更、无破坏性迁移；`tb_progress` / `tb_word_memory` 不做物理删除。
- 后端测试：`rewrite-service` **197 项**、`cardback-editor` 6 个测试脚本全部通过（health 31 / hardening 20 / sync_store 67 / sync_epoch 20 / cards_publish 36 / TLS 9，TLS 有 1 项按环境跳过）。
- Android 单元测试：`prod` 39 个测试套件 / **251 项**，`dev` 39 个测试套件 / **251 项**，均 0 失败。
- `versionCode = 26`、`versionName = "1.5.0"`；`prod` / `dev` release APK 证书 SHA-256 均为 `963533138ed73e92e9a2859f80fdf18becb4ee5a83132c55634b965d7164ba1c`。

## v1.4.9-P0（2026-11-22）— 先保命，再提速

这一版不加新功能，只解决两件会「毁数据 / 卡进度」的事：后台改写会卡死，以及重装/换机后进度和书可能对不上。

### 一、后端：6 槽防卡死 + 5 处稳健性修复

- **6 槽并发（`WB_GATE_SLOTS=6`、`REWRITE_CONCURRENCY=6`）**：原来一把大锁把整批任务串起来，46 个词跑了 1832 秒只完成 5 个（约 9.83 词/小时）。现在按槽位放行，最多 6 条并行，单条失败不再拖住整批。
- **修僵尸 `running`**：worker 异常退出时会把 `claimed` 状态释放掉，队列里不会留下永远「进行中」的假任务。
- **`wb_gate._write_json` 并发重试**：多槽同时写状态文件时失败会重试，不再偶发写坏 gate 状态。
- **`_work_batch` 重设优先级**：批量任务重新入队时优先级会被正确重置，不再出现新任务排在旧任务后面。
- **`find_reusable_result` 精确书范围**：复用成品时只在同一本书里找，避免把别的书的释义串过来。
- **`extract_json` 取第一个 `{` 再 `raw_decode`**：模型输出前后带解释文字时也能正确解析。
- 默认参数：`WB_GATE_SLOTS=6`、`REWRITE_CONCURRENCY=6`、`BATCH_MODE=0`（单条模式）、`WB_GATE_WAIT_TIMEOUT=300`。
- 后端测试：**197 项全部通过**。

### 二、Android：签名、双开隔离、预设书保进度

- **release 固定签名**：release 与 debug 统一绑定 `wordhelper.jks`，构建后自动校验 APK 证书 SHA-256，指纹不是 `963533138ed73e92e9a2859f80fdf18becb4ee5a83132c55634b965d7164ba1c` 就直接构建失败，杜绝「装上去提示签名不一致、只能卸载重装」。
- **`prod` / `dev` 双 flavor**：正式包 `com.kaoyan.wordhelper`，实验包 `com.kaoyan.wordhelper.dev`（应用名带「·实验」），两个包可以同时装在一台手机上，互不覆盖数据。
- **预设书软停用**：预设书过期/重复时不再物理删除，而是用 `total_count=-1` 软停用；`tb_progress` 和 `tb_word_memory` 里的学习记录一条不删，书回来进度还在。
- **`syncPresetWords` 改内容指纹**：只有词库内容真的变了才刷新，避免每次启动重复写入。
- Android 测试：`prod` 36 个测试套件 / **228 项**，`dev` 36 个测试套件 / **228 项**，均 0 失败。
- 四个 APK 证书指纹均为 `963533138ed73e92e9a2859f80fdf18becb4ee5a83132c55634b965d7164ba1c`。

### 三、数据与兼容

- Room 数据库版本保持 **24**，没有破坏性迁移；从 1.4.8/1.4.9 升级不需要清数据。
- 词库 `assets/cet6_cards.json` 保持 v3，未被旧审计脚本覆盖。
