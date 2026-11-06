# 「不爱背单词」(english__app) 作为 CET-6 图文记忆系统底座的调研

**结论先行：有条件推荐。** 它是一个完整可用、代码质量明显好于"vibe coding"自述的背单词 App，词库/发音/拼写/SM-2/统计/设置全都现成，但你要的四样核心能力——**图片、三段式正式内容、ZIP 导入更新、含媒体备份——一个都没有**（全仓 `Bitmap|coil|Glide|ZipInputStream|ZipFile|filesDir|ImageDecoder` grep 命中 0）。所以这不是"魔改补几个功能"，而是"在别人的壳里做你自己的产品"：按代码量算你能复用 85% 以上，但按"决定这个产品能否成立"的工作量算，新增部分占一半以上。**如果你的目标是"更好的记忆系统"，它比 AnkiDroid 差；如果你的目标是"一个自己的背词 App"，它是目前最省力的底座。**

调研方式：克隆到 `_recon\repo`，静态阅读 + 交叉验证；**未安装依赖、未执行构建**（遵守限制）。GitHub API 在首轮后触发 403 限流，star/fork 未取到。

---

## 1. 已实现功能及证据

| 能力 | 结论 | 证据 |
|---|---|---|
| 词库 | 预置两本：5671 词 / 20863 词，**没有一本是 CET-6** | 仓库根 `wordbook_full.json`(16.7MB, `_meta.total=5689`, `failed=18`)、`wordbook_full_from_e2c.json`(42.0MB, 20863 条)；构建期由 `app/build.gradle.kts:64-74` 拷进 assets |
| CET-6 覆盖 | 精选书 4574/5253=**87.07%**；完整书 5134/5253=**97.73%**；两库并集 5203/5253=**99.05%** | 用你 `word-runs` 的 8098 个 run 名剥时间戳得 5253 个唯一词比对；并集仍缺 50 词（app/atop/best-seller/cliche/mindset/smartphone/workout…） |
| 学习流程 | 卡片三键评分、拼写模式、生词本、搜索、统计图表 | `LearningScreen.kt`(2044 行)、`SpellingEvaluator.kt`、`ProfileScreen.kt` |
| 间隔重复 | SM-2 核心公式正确，另有"艾宾浩斯阶梯"变体 | `Sm2Scheduler.kt:297` `ease - 0.8f + 0.28f*q - 0.02f*q*q`；阶梯 `intArrayOf(1,2,6,14,30)` |
| 发音 | **在线**：有道 dictvoice + dictionaryapi.dev 兜底，**无本地缓存** | `PronunciationRepository.kt:69,104`（`https://dict.youdao.com/dictvoice?audio=…&type=2`） |
| 导入 | 仅 TXT/CSV/JSON 文本，产出 7 字段 `WordDraft` | `WordFileParser.kt`(509 行) |
| 备份 | 单个 `.db` 文件整体拷贝 | `DatabaseBackupManager.kt:13-27` |
| AI 助记 | BYOK 纯文本，四类内容，助记输出已被强制排成**三段** | `AIContentFormatter.kt:41-57`【词根词缀】【联想记忆】【复习提示】 |
| 图片 / ZIP / 媒体备份 | **零** | 全仓 grep 0 命中 |
| 许可证 | Apache-2.0，可闭源商用魔改 | `LICENSE`(202 行，无 NOTICE) |
| 维护度 | 33 个提交、单人、2026-02-13→03-24 高产 40 天后停更；v1.2.2(2026-03-10) | git log；README 更新日志 |

> 注意：Issue #3（鸿蒙闪退，已 closed）的栈是 `SQLiteException: too many SQL variables`，语句为 `SELECT * FROM tb_progress WHERE word_id IN (?,…)`——**这个项目在 2 万词规模上真的撞过 999 变量上限**，是规模化的现成前科。

---

## 2. 关键代码结构

- **技术栈**：Kotlin 2.0 + Compose(M3) + Room/KSP + Retrofit + DataStore + Jetpack Security；minSdk 26 / target 34 / compileSdk 34。
- **数据库**：`AppDatabase.kt:41-59`，`version=13`，13 张表，12 个手写 Migration，`exportSchema=false`（迁移无 schema 校验）。
- **三张核心表**：
  - `tb_word(id, word, word_key UNIQUE, phonetic)` — 词条身份靠 `word_key` 唯一索引，跨书共享。
  - `tb_book_word_content(word_id, book_id, meaning, example, phrases, synonyms, rel_words)` — `UNIQUE(word_id,book_id)`，写回是 **REPLACE**。
  - `tb_progress(...16 列, UNIQUE(word_id,book_id), index(next_review_time))` — **无外键**，所以改词条内容不会级联删进度（这是好消息）。
- **唯一入库口**：`WordRepository.upsertDraftsForBook`(:928-1002) — 按 `word_key` 分块查重（900/批），新词 IGNORE 插、内容 REPLACE 批插、单事务包全程。
- **复习队列**：`ProgressDao.getDueWords`(:37-42) `status != 2 AND next_review_time <= now`；`getNewWordsList`(:170-191) 也 `NOT IN status=2`。
- **三段式现成接缝**：卡片背面已有 `WordExtraSection` 三块（`LearningScreen.kt:1786-1788` 短语/近义词/同根词），AI 助记也已固定三段格式——**"每词三段学习思路"的 UI 形态和内容形态，这个项目里都已经存在，只差把它变成正式字段**。

---

## 3. 需新增 / 修改的功能与复杂度

| # | 要做的 | 落点 | 复杂度 |
|---|---|---|---|
| 1 | 词库瘦身 + 懒加载：**留 5671 精选当词典底册**（释义/音标/短语/近义词/例句都在这），删 20863 那本（-42MB），把"冷启动全量反序列化"改成首启后台分批入库 | `app/build.gradle.kts:64-74` + `wordbook_full_from_e2c.json` + `WordbookFullLoader.kt:176-247` + `KaoyanWordApp.kt:75-83` | M |
| 2 | 新表 `tb_word_image`(word_id, rel_path, sort_order) + `tb_word_note`(三段文本/来源) | `AppDatabase.kt:41-59`、新增 entity/Dao、`MIGRATION_13_14` | M |
| 3 | 图片落盘 `filesDir/images/<word_key>/`，DB 只存相对路径；引入 Coil | 新建 `ImageStore.kt`；`LearningScreen.kt:544` 卡片背面渲染 | M |
| 4 | ZIP 导入（manifest + images/，zip-slip 防护，按 word_key 去重，内容覆盖、不动 progress） | 新建 `ZipImporter.kt` + `ManifestParser.kt`；改 `BookManageViewModel.kt:155-192`（不能先 `readText()`）、`WordFileParser.parse` 加分支 | **L** |
| 5 | 增量更新（同词再导入 = 更新内容而非跳过） | `WordRepository.kt:957-959` 命中即 return、`upsertDraftsForBook` 加 update 分支 | M |
| 6 | 备份扩展为 zip（db + images + DataStore 设置），恢复前校验+回滚 | 重写 `DatabaseBackupManager.kt:13-44`；`ProfileViewModel.kt:125-147` | M |
| 7 | 调度补丁：MASTERED 不再永久退役 | `ProgressDao.getDueWords` / `resolveStatus` | S |
| 8 | 复习日志表（为将来换算法留退路） | 新表 + `WordRepository.applyStudyResult` 内追加写 | M |

> 注：三段式**不要**塞进 `tb_ai_cache`，也别拿现成的 AI 助记当正式内容。该表无过期（`AICacheDao.kt:31` 的 `deleteOlderThan` 生产代码零调用）、缓存键不含 prompt 版本、`forceRefresh` 只插新行，内容一旦被当正式数据，改词永远不生效。

合计：**新建约 8 个文件、改 12–15 个，代码量 1,300–2,000 行**，2 张表、1 次迁移。对一个人 + AI 辅助，乐观 3–5 天，现实 8–12 天。

---

## 4. 技术风险与隐藏成本

**A. 调度层是降级，不是持平**
- `status=2`(MASTERED) 被 `getDueWords` 和 `getNewWordsList` **双双排除** → 一个词间隔涨到阈值（V3：21 天 + 2 次复习；V4：30 天 + 4 次）就**永久消失**，只剩统计数字。长期记忆没有"越背越稀"的尾部——对 5200 词的半年项目，这是会真正发生的。
- 遗忘时 `ease - 0.8` 之外再 `ease - 0.2`（`Sm2Scheduler.kt:57-58`），单次遗忘掉 1.0，比 SM-2 严厉。
- **无 fuzz、无 learning steps、无 leech、无 suspend/bury**；`AGAIN` 在 V3 直接排到"明天 4 点"。
- **无逐次复习日志**（`tb_study_log` 只有 `date + count`）→ 复习历史不可回溯、不可重算，将来想换 FSRS 等于从零。这是"复习数据可靠性"上最硬的一条。

**B. 数据与备份**
- 备份就是裸拷一个 db 文件，**无校验、无回滚、不重开库**，恢复失败只提示重启（`ProfileViewModel.kt:142`）。
- `allowBackup=true` 同时 Key 存 `EncryptedSharedPreferences`（`AISecureStorage.kt:45-56`）→ 换机恢复后 Keystore 不随迁，`loadConfig()` 有可预期的崩溃面。

**C. 性能（5200 词 / 1 万图）**
- 冷启动 `gson.fromJson` 一次性反序列化 **108,160 条**（`WordbookFullLoader.kt:247`），缓存单例常驻内存；`loadWordbookCache` 全程 `synchronized`，而 `LearningScreen` 组合期会主线程触发 → **ANR 级卡顿/中低端机 OOM**。删掉 20863 那本能砍掉 78,509 条（约 3/4），但要彻底解决得把解析移出主线程可达路径并分段入库——这是第 0 步，不是优化项。
- 首启单事务内逐词单行 insert（`WordRepository.kt:964`），2 万次写；导入 5200 词时同样是"整文件 readText + 主线程 parse + 全量 drafts 驻留 + 单事务"，会 ANR。
- 队列每次刷新拉整本词再 Kotlin 过滤（`getStudyQueueSnapshot:584-668`）；搜索 `LIKE '%q%'` 全表扫无 FTS。
- 体积要提前算：5200 词 × 2 张图、每张 300KB ≈ **3GB** 私有目录；备份 zip 也在这个量级（打包+校验要按分钟计），建议统一 webp、长边 1024、单张压到 150KB 内。

**D. 构建与维护**
- **没有 gradle wrapper**（`gradle/` 下只有 `libs.versions.toml`），README 让你自己用本机 `gradle` 编 —— 无法复现作者环境，这是第一道门槛（也是我按限制唯一没实测的环节：依赖版本组合自洽，静态看不出冲突）。
- targetSdk 34 / 依赖停在 2024.05 组合；`security-crypto` 是 alpha 且官方已弃用；MPAndroidChart 2019 年后停更 + jitpack 依赖不稳。
- 单人项目、40 天高产即停，`LearningScreen.kt` 2044 行、`WordRepository.kt` 1121 行上帝类；最大两个文件**无直接单测**。

---

## 5. 与 AnkiDroid 的必要比较

| 维度 | 本 App | AnkiDroid |
|---|---|---|
| 图文卡片 | **无**，要自建 | 已有（字段级媒体，你 V7 模板 + 5224 张 A 图已在跑） |
| 批量导入 | 无 ZIP，文本 7 字段 | apkg（含媒体），支持 "update notes" 增量 |
| 完整备份 | 裸 db 文件 | colpkg = 库 + 媒体，成熟 |
| 调度可靠性 | SM-2 + 30 天封顶 + 词退役，**无复习日志** | SM-2/FSRS，`revlog` 完整可重放 |
| 离线 | 除发音外可离线（发音走在线 URL） | 全离线 |
| 长期可迁移 | 数据锁在 Room，导出要自己写 | apkg 生态，事实标准 |
| 日常背词体验 | **强**：一键三评、拼写、生词本、统计、单 App 闭环 | 弱：模板化、卡片浏览笨重、手势/统计割裂 |
| 开发成本 | 代码量复用 85%，仍需 8–12 人日（新增部分才是难点） | 0 |

**读法**：你要求的每一项功能，AnkiDroid 现在都已经有；本 App 唯一赢的地方是"日常手感 + 一个 App 说完"。你在 Anki 上踩的坑（媒体丢失、`update_notetypes=NEVER`）本质是**你自己的导入脚本**没写对，不是 Anki 能力缺失——而这个坑换成自研 ZIP 导入，一样要重新踩一遍，只是这次你能改。

---

## 6. 裁决

**有条件推荐 —— 推荐作为"自用背词 App"的底座，不推荐把它当成"比 AnkiDroid 更好的记忆系统"。**

- 如果你要的是**功能**：不推荐。5200 词 + 三段式 + 三张图 + 备份，AnkiDroid + 修好的导入脚本今天就能跑，零开发、数据可靠一个数量级。
- 如果你要的是**一个自己的 App**（AnkiDroid 的手感确实劝退，而你又要长期每天打开它）：推荐。这是我能找到的最省力路径——省掉从零写 Compose 学习流、词典、拼写、统计、设置的全部工作。
- 选择它就必须接受三条**前置代价**，不接受就别开：① 调度比 Anki 弱且要自己补（退役逻辑、复习日志）；② 备份要从零做（含媒体、要能校验）；③ 词库要自己出（内置书不是 CET-6，且只覆盖 87%）。

### 最小可行改造范围（不实施，仅界定）

**P0 必须先做（否则不可用）**
1. 词库瘦身 + 懒加载：**保留 5671 词那本当词典底册**（"保留现有词典"就靠它），删掉 20863 那本，冷启动的全量反序列化改成首启后台分批入库。注意精选书缺 679 词，这批词的释义/音标必须由 ZIP 自带，否则它们只有三段式、没有词典义。
2. 两张新表 + `MIGRATION_13_14`：`tb_word_image`、`tb_word_note`（三段正文 + 来源）。
3. 图片落 `filesDir/images/`，DB 存路径，Coil 渲染在卡片背面（**不要存 BLOB**，否则备份必丢图）。
4. ZIP 导入：`manifest.json` + `images/`，zip-slip 白名单、word_key 去重、内容覆盖而 progress 不动、分批事务 + 进度条。
5. 备份改 zip（db + images + 设置），恢复前校验。
6. 调度补丁：MASTERED 词仍进复习队列（或把阈值抬到 90 天以上）。

**P1 建议（成本低、保命）**
7. 复习日志表 `tb_review_log`，每次评分追加一行 —— 这是"数据可靠性"的最低成本保险。
8. 修 `syncPresetWords` 的 `if (currentCount == drafts.size) return`，否则改内置书内容永远不生效。

**明确不做**：动效、云同步、UI 重构、AI 多模态出图（`ChatMessage.content` 是 `String`，要图得动请求体/响应体/缓存表/依赖四处，收益不抵成本）。
