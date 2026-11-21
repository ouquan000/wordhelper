# 更新日志

本文件记录「不爱背单词」（wordhelper）的版本变更。日期为北京时间。

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
- 后端测试：`rewrite-service` **197 项**、`cardback-editor` 6 个测试脚本全部通过（health 31 / hardening 31 / sync_store 67 / sync_epoch 20 / cards_publish 36 / TLS 9，TLS 有 1 项按环境跳过）。
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
