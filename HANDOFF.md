# 交接文档（新会话从这里开始）

> 生成时间：2026-11-06 19:22（1.4.1preview 已交付、已入库；准备开发 1.4.2）
> 仓库根：`D:\deepseek harness\work_anki_enligish`
> 当前 HEAD：`9689807`「1.4.1preview：卡片翻面手势改造 + 独立验收 100 分」；工作区**干净**
> 提交线：`faf823e` 1.3 → `62cccf9` 1.4 内置 5228 词种子 → `cf8c80c` 调试纪律 → `4ad923d` 交接 → `9689807` 1.4.1preview
> 用户手机现装：**1.4.1preview（versionName 1.4.1preview / versionCode 4）**，已用**无线 adb** 接管（见 §4）

---

## 1. 一句话现状

把开源安卓背单词 App「不爱背单词」改造成**我自己的六级图文记忆卡**：1.4 已把 **5228 个词（含记忆思路 + 常用搭配 + 中英例句）内置进 APK**，首启自动导入成「六级记忆卡」并设为当前词书。**1.4.1preview（versionCode 4）** 修完了「卡背上下滑误翻面 + 点一下有水波纹」：去掉 `Card(onClick)` 换成非可点重载，翻面改自研 `Modifier.cardFlipTap`（4dp 位移门控 + 未被消费才翻），并给 `AnimatedContent` 补 `contentKey = word.id` 修掉「点星星连带翻面」。独立评分员按冻结规格跑出 **100/100**，报告在 `docs/report-1.4.1-test.md`。

**当前状态**：1.4.1preview 已装到真机、代码已提交（`9689807`），等用户定 1.4.2 做什么。候选：同步按钮（用户已同意排到下个小版本）、两份墨墨报告修正、卡背标记问题换思路。

---

## 2. 目录地图

| 路径 | 是什么 |
|---|---|
| `app/src_repo/` | 安卓源码（**已删除嵌套 .git**，上游基线 c0a7fe0） |
| `app/src_repo/app/src/main/java/com/kaoyan/wordhelper/` | 主代码；改动集中在 `data/entity/WordMemory.kt`、`data/dao/WordMemoryDao.kt`、`data/dao/WordDao.kt`、`data/database/AppDatabase.kt`、`util/WordFileParser.kt`、`ui/screen/LearningScreen.kt` |
| `content/words/test20.json` | 20 词试验导入包（20,719 B） |
| `content/seed/cet6_cards.json` | **1.4 内置种子**（5228 词，4.70 MB，schema 同 test20 + version/book 元信息） |
| `tools/build_seed.py` | 由 `by-word/*.json` 重新生成上面这份种子（纯脚本，零模型） |
| `dist/english-app-1.3-t20.apk` | 1.3 版 APK（30,723,515 B，versionCode 2） |
| `dist/english-app-1.4-cet6.apk` | **1.4 版 APK（32,998,851 B，versionCode 3，内置 5228 词）** |
| `docs/shots/` | 卡背验收截图 3 张 |
| `docs/report-maimemo-card-format.md` | 墨墨卡片格式报告（8 节，已交付） |
| `docs/report-maimemo-sync.md` | 墨墨同步报告（8 节，已交付） |
| `_toolchain/` | Gradle 8.9、gradle-home、**签名 wordhelper.jks**（不入库） |
| `_recon/maimemo/` | 墨墨 APK 拆包产物（APK、REPORT.md、res_dump.txt、kw_hits.txt） |
| `media/`、`sync-service/` | 预留（图片、同步服务），目前空壳 |
| `report_english_app_评估.md` | 最初的可行性调研报告（六节） |

---

## 3. 已交付的能力（1.3）

1. **记忆思路**：卡背在释义下方显示 `derivation` 的 ①②③，多行。
2. **常用搭配**：卡背显示最多 4 条，格式 `英文 —— 中文`。
3. **释义按词性分行**：`v. 增加；提高` 和 `n. 增长；增加量` 分两行（正则 `MEANING_POS_BREAK`，两路并联：`[；;]\s*(?=pos\.)` 与 `\s+(?=pos\.)`）。
4. **数据库 v14**：新增 `tb_word_memory(word_id PK, approach, collocations, updated_at)`，FK→`tb_word` ON DELETE CASCADE，无 DEFAULT。
5. **导入格式扩展**：`WordFileParser` 认 `approach`/`derivation`/`memoryApproach`/`memory` 与 `collocations`/`collocation`/`chunks`。
6. **可覆盖升级的签名**：`_toolchain/signing/wordhelper.jks`（alias `wordhelper`，密码 `wordhelper123`，RSA2048/10950 天）。**这个文件丢了，手机上的版本就永远无法覆盖升级**，务必单独备份。

### 1.4 内置种子（本轮新增）

7. **种子文件** `assets/cet6_cards.json`：5228 词，字段 `word/phonetic/meaning/example/approach[]/collocations[]`，带 `version` 与 `book`（「六级记忆卡」）。
8. **`CardSeedLoader`**（`data/model/CardSeed.kt`）：优先读 `/sdcard/Android/data/<包名>/files/cet6_cards.json` 或 `filesDir` 同名文件（**adb push 换词不重装**），否则读 assets。
9. **`WordRepository.ensureCardSeedBook(seed)`**：建/复用「六级记忆卡」（TYPE_IMPORTED），复用 `upsertDraftsForBook` 写词义/例句/思路/搭配；**只 REPLACE 内容，不碰 tb_progress**，重导不重置进度；无活跃词书时自动设为当前。
10. **版本门控**：`SettingsRepository.cardSeedVersion`（DataStore `card_seed_version`），种子 `version` 变大才重导——绕开 `syncPresetWords` 的 `currentCount == drafts.size` 早退。
11. 真机验收：词书 4 本 / 累计 31754 条，「六级记忆卡」5228 条且为当前；`tb_word_memory` 5228 行；卡背 `increase` 显示释义两行 + ①②③ + 4 条搭配 + 中英例句；三键为 不认识/模糊/认识。

验收证据：导入后词书 3→4 本、累计词条 26526→26546（+20）；`increase` 两行词性；`adolescent` 思路 ①②③④ 完整；上下滑可滚，左右翻卡在背面被上游 `enabled = !isAnswering` 禁用（正常）。

---

## 4. 怎么构建 / 装 / 测

```bash
export JAVA_HOME="/c/Users/ouquan/AppData/Local/Programs/Microsoft/jdk-17.0.10.7-hotspot"
export GRADLE_USER_HOME="/d/deepseek harness/work_anki_enligish/_toolchain/gradle-home"
cd "/d/deepseek harness/work_anki_enligish/app/src_repo"
"/d/deepseek harness/work_anki_enligish/_toolchain/gradle-8.9/bin/gradle" --no-daemon :app:assembleDebug
```

- 产物：`app/src_repo/app/build/outputs/apk/debug/app-debug.apk`
- `local.properties` 必须写 **正斜杠** `sdk.dir=D:/Android/Sdk`（反斜杠会被 Properties 解析吃掉，报「文件名、目录名或卷标语法不正确」）
- 装：`adb install -r <apk>`（同签名才能覆盖；换签名要先卸载，进度会丢）；降级覆盖要加 `-d`
- **adb 不在 PATH 里**：能用的是 GlideX 自带的 `/c/Program Files/ASUS/GlideX/adb.exe`（5037 端口已在跑）；Git Bash 里调 adb 前 `export MSYS2_ARG_CONV_EXCL='*'`，否则 `/sdcard/...` 会被改写成 Windows 路径
- 模拟器：AVD `mf`（API 34 x86_64，headless + swiftshader）；真机 `10AF941PZJ00AKH`
- **真机已可无线操作**（用户 11-06 19:10 授权接管）：`adb connect 10.103.216.69:5555`，见下方「无线 adb」
- **默认在模拟器验，真机只在交付时碰且先问用户**（见 §8）
- 验收要看卡背：`adb shell input swipe` 上滑 + `adb exec-out screencap -p > x.png`
- 拉 App 数据库：`adb exec-out "run-as com.kaoyan.wordhelper cat /data/data/com.kaoyan.wordhelper/databases/kaoyan_words.db" > D:/xxx.db`（还要一起拉 `-wal`/`-shm` 才有最新提交）

### 无线 adb（11-06 已验证可用）

```bash
ADB="/c/Program Files/ASUS/GlideX/adb.exe"
export MSYS2_ARG_CONV_EXCL='*'
# 首次要 USB 连着跑一次；之后手机不重启就一直有效
"$ADB" -s 10AF941PZJ00AKH tcpip 5555
"$ADB" connect 10.103.216.69:5555
"$ADB" devices                              # 应同时列出序列号与 IP:5555
"$ADB" -s 10.103.216.69:5555 shell echo OK  # 确认无线真的通了再拔线
```

- **别信手机「无线调试」页面上显示的 IP**：显示 `172.19.0.1:34427` 是 `tun0` 的 VPN 虚拟网卡，电脑连过去必然超时（10060）。真实地址用 `adb shell ip addr show | grep 'inet '` 看 **wlan0** 那一行。
- **持久性**：`service.adb.tcp.port=5555` 在手机重启前一直有效；重启后 adbd 退回 USB 模式，需要重新 `tcpip 5555`（不想插线就走系统「无线调试」+ 它的端口）。
- **命门**：锁屏不影响连接，但 vivo 省电策略可能掐 WiFi；换 WiFi 会换 IP（DHCP）。本机 `adb mdns check` 报 `mdns daemon unavailable`，没有 mDNS 自动发现，IP 变了要手动重连。

**版本号铁律**：每次发版 `versionCode` 必须 +1（当前 **3**，下次 4）；DB 版本当前 14，下次改动必须 15。

---

## 5. 已经踩过的坑（别重犯）

1. **Room 加字段**：实体加字段后，**每一条 `@Query` 的返回列都必须补齐**，否则 KSP 报 `The columns returned by the query does not have the fields [...]`。本次 `searchWords` 就漏了一次。
2. **`rootProject.file("..")`** 的相对基准是 **settings.gradle.kts 所在目录**，不是模块目录；写 `../_toolchain` 会解析成 `app/_toolchain`，要用 `../../_toolchain`。
3. **签名路径**：`signingConfigs.getByName("debug")` 覆盖默认 debug 签名，路径错只会报 `validateSigningDebug` 阶段找不到文件。
4. **C 盘**：用户明确说别往 C 盘下载东西。Gradle 走腾讯镜像 `mirrors.cloud.tencent.com/gradle/`（20MB/s），Maven 走 `_toolchain/gradle-home/init.d/mirrors.gradle`（阿里云 public/google/gradle-plugin + jitpack）。
5. **本 App 每次导入都新建一本书**，分片更新会越堆越多；要干净更新得先给导入加「更新已有词书」模式（约半天）。
6. **内置词书内容改了不生效**：`syncPresetWords` 有 `if (currentCount == drafts.size) return` 早退。
7. **git clone 上游** 需要 `-c http.sslVerify=false`（本机 TLS 握手会断）。
8. **Compose 点按阈值 8dp**：`Card(onClick)` / `clickable` 只在小位移下判点按，这个阈值远大于「人以为的滑动」——凡是「滑动也会触发点击」的 bug，先怀疑它，别去怀疑手势库。
9. **本机 logcat 不收 D 级日志**：`adb logcat -d --pid=<pid>` 里 D 行恒为 0，埋点要用 `Log.w` 才看得见。
10. **拉数据库必须带 WAL**：`kaoyan_words.db` 之外还要拉 `-wal`、`-shm`（命名成 `xxx-wal`/`xxx-shm` 放同目录），否则读到的是旧快照，会得出「进度没写进去」的错误结论。
11. **手机「无线调试」页显示的 IP 可能是假的**：vivo 开了 VPN/代理后显示 `172.19.0.1:34427`（`tun0` 地址），电脑连必超时（10060）。真实地址永远是 `ip addr` 里 `wlan0` 那一个。另外本机 `adb mdns check` 报 `mdns daemon unavailable`，别指望自动发现。
12. **`AnimatedContent` 的 `targetState` 带非标识字段 = 整棵树重建**：本项目 `cardState = WordCardState(word, isInNewWords)`，点星星改了收藏状态，就被判成「换了张卡」，旧卡销毁、`remember(word.id)` 全归零，翻面状态跟着丢、还白播一次切换动画。修法是加 `contentKey = { it.word.id }`。凡是「改了个小状态，整个组件状态莫名回到初始」的怪现象，先查这条。

---

## 6. 待办（按优先级）

1. **【已完成 ✅ 1.4.1preview】卡背「上下滑误翻面 + 点一下有水波纹」**
   - 做法：`Card` 换非可点重载（去 ripple）；翻面改自研 `Modifier.cardFlipTap`（`awaitFirstDown(Initial, requireUnconsumed=false)` + 累计最大位移 ≤4dp + 抬手未被消费）；`AnimatedContent` 补 `contentKey = word.id`。
   - 结果：独立评分 **100/100**（`docs/report-1.4.1-test.md`），F5 水波纹色差 0.00/0.00%（旧包 19.71/98.13%）。
   - 遗留：长按 1.5 秒松手仍会翻面（与旧版 `Card(onClick)` 一致，非本次引入）；E1 动画连贯性只在模拟器 3fps 下录过，真机没测。
2. **【1.4.2 候选·用户已同意】「同步按钮」**：用户要「点个同步按钮刷新卡片内容」，明确说排到下一个小版本。
3. **【待做】两份墨墨报告的三处修正**：四键→三键、助记可带图（图存 OSS 不入库）、同步补 oplog 增量（手动按钮才是整包）。用户已口头确认口径，只差改文档。
4. **【可选项】清掉两本内置大词库**（完整词库 20863 / 精选 5663）：种子词书已替代它们，但目前没删——`WordbookFullLoader` 的读音索引还依赖那两个大 JSON，删词书不删 assets 才安全。
5. **【待用户确认】左滑「太简单」仍易误触**：判定线是卡片宽度的 30%（`SWIPE_TRIGGER_RATIO = 0.3f`，有效区 0.8），横着蹭一下就可能把词判成「太简单」直接跳走。等用户确认他说的「反面的效果」是翻面还是换词，再决定要不要收紧到 40~50%。
6. 之后才做的事：图片（5183 词已有一张 A 图，832×1216）、「卡背标记有问题→次日换新思路」、同步服务。
7. **改种子内容的正确姿势**：改 `by-word` 数据 → `python tools/build_seed.py`（或手改 cet6_cards.json）→ `version` +1 → 重新打包安装；不想重装就 `adb push cet6_cards.json /sdcard/Android/data/com.kaoyan.wordhelper/files/` 后重启 App。

---

## 7. 内容资产在哪（不在本仓库）

| 资产 | 路径 | 数量 |
|---|---|---|
| 词条文本（32 字段） | `D:\deepseek harness\workcodex\.tmp\export\by-word\<word>.json` | 5249 份 |
| A 图（832×1216 png） | `D:\deepseek harness\workcodex\.tmp\img-a\<word>\assets\viewa\<word>-viewa.png` | 4859 张 / 5183 词 |
| 权威词表 | `work——anki\pipeline\known_vocabulary_base\source\cet_full_list.json` | 5272 词 |
| V7 卡片工程（参考，不用） | `...\od-permissions-20261027\...\anki\v7-card-*.html/css/js` | 4 文件 |

词条字段关键：`word / phonetic / pos / meaning / method / derivation[①②③] / collocations[2-4] / example / exampleZh / forms`。
缺口：4 词无文本（action/from/resource/responsibility）、9 词元数据坏（and/by/for/in/on/that/they/to/you）。

---

## 8. 用户偏好与红线

- **能自动就自动，别让他动手**；只在关键决策（选方案、动数据、发版）找他。
- **结论优先**，不要罗列工具名；改动编号列出；验收要真机证据。
- 不要在 C 盘堆东西；保持仓库卫生。
- **不要擅自开始写代码**，等他一句「开始」。
- **调试纪律：模拟器随便折腾；真机默认不碰，碰之前问一句。** 例外：2026-11-06 19:10 用户主动说「我连接上有线 adb 了…要啥自己拿」并开了无线 adb，等于授权接管。授权范围内可直接读数据、看界面；**要动屏幕（点/滑/装包）之前先打声招呼**，因为那会跟他抢手指。
- **不为一个 bug 连升版本号**：内部验证包用同版本号覆盖安装，只有他要留档/发版才升号。
- 不动他正在用的 DSH / Anki 环境，不碰他账号密码。
- 他今晚就要开始背单词，**速度 > 完美**。

---

## 9. 新会话怎么开局

1. **先读本文件**，再跑 `git log --oneline -3` 确认 HEAD 在 `9689807`、`git status` 干净。
2. **别急着改代码**：用户红线是「没被叫就不许动手」。第一句先问他要做什么，只有他说「开始/改」才动。
3. **真机**：`10AF941PZJ00AKH`，已可无线接（`adb connect 10.103.216.69:5555`，见 §4）。但默认仍先在模拟器 `mf` 上验；真机要动屏幕之前说一声。
4. **接手时的状态**：1.4.1preview 已交付（独立评分 100/100）；1.4.2 候选见 §6 第 2 条（同步按钮），另外左滑易误触还等他拍板。
