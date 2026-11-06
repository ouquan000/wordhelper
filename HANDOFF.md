# 交接文档（新会话从这里开始）

> 生成时间：2026-11-07 01:00（**1.4.2 已验收通过**，打了 annotated tag `v1.4.2`）
> 仓库根：`D:\deepseek harness\work_anki_enligish`
> 当前 HEAD：`be76528`（tag `v1.4.2`）；工作区**干净**
> 提交线：`faf823e` 1.3 → `62cccf9` 1.4 内置 5228 词种子 → `cf8c80c` 调试纪律 → `4ad923d` 交接 → `9689807` 1.4.1preview → `573dc7c` 冻结 1.4.2 验收规格 → `1f4a86d` 清单 schema 契约 → 卡背按清单渲染 / 编辑器与服务 / 契约与 README / 版本号与证据 / B6 修复 / exe 打包 / 评分报告（完整列表见 `git log --oneline`）
> 用户手机现装：**1.4.1preview（versionCode 4）**，已用**无线 adb** 接管（见 §4）。`dist/english-app-1.4.2.apk`（versionCode 5）**已出包、已验收、还没装真机**
>
> ⚠️ **用户红线**：没说「改」不要动代码/装机/升版本；说「先停一下」必须真停；修 bug 不连跳版本号；改完一处先给可验证结果；**他不看截图**，交付只给「结论 + 报告 + 下载链接 + 等他决策的事」。
> 📖 涉及 1.4.2 设计先读 [`docs/decisions-1.4.2.md`](docs/decisions-1.4.2.md)，验收口径见 `docs/test-spec-1.4.2.md`（sha256 锁在 `.lock`）。用户已经答过的问题不要再问一遍。

---

## 0. 从哪开始（新窗口必读）

1.4.2 已封版：测试 agent 98/100 → 修 B6 → 评分 agent 独立复核 **100/100**，K1–K5 全未触发。**新窗口做什么由用户指定，不要自己挑活开工。**

候选三件（我建议的顺序 A → B → C）：

- **A. 装真机**（十分钟、无风险，**需用户点头**）：把 1.4.2 装到 `10AF941PZJ00AKH`。装前先比证书指纹（`apksigner verify --print-certs` vs 已装包），签名不同绝不能卸载重装（会删进度）。
- **B. 接动效**（半天）：清单里参数已写好（820 / 90 / 220 / 72% / -1 / 1.0015），App 还没接管，现在翻面动画是写死的。接上后编辑器就能连动效一起调。
- **C. 1.4.3 正题**：重写按钮 + 模型下拉 + 折叠输入框 + 离线标记队列 + 电脑端改写服务。三个必须先问用户的点：① 按一下改写整张卡背还是只改他圈的那段；② 用哪个模型、走 WB 网关还是他的 key、要不要花钱；③ 改完不满意是覆盖还是保留旧版对比。

**当前后台进程**：临时下载服务 `python -m http.server 8899 --directory dist`（PID 43076，手机下 APK 用，用户下完就关；防火墙规则名 `anki-dist-8899`）。模拟器与 8765 服务均已关闭。

---

## 1. 一句话现状

把开源安卓背单词 App「不爱背单词」改造成**我自己的六级图文记忆卡**：1.4 已把 **5228 个词（含记忆思路 + 常用搭配 + 中英例句）内置进 APK**，首启自动导入成「六级记忆卡」并设为当前词书。**1.4.1preview（versionCode 4）** 修完了「卡背上下滑误翻面 + 点一下有水波纹」：去掉 `Card(onClick)` 换成非可点重载，翻面改自研 `Modifier.cardFlipTap`（4dp 位移门控 + 未被消费才翻），并给 `AnimatedContent` 补 `contentKey = word.id` 修掉「点星星连带翻面」。独立评分员按冻结规格跑出 **100/100**，报告在 `docs/report-1.4.1-test.md`。

**当前状态（2026-11-07）**：**1.4.2 已验收通过并封版**（`dist/english-app-1.4.2.apk`，versionCode 5 / versionName 1.4.2，33,221,506 B，sha256 `2f7101da…`），tag `v1.4.2` 指向 `be76528`；**真机未装**。

### 1.4.2 做了什么（已交付）

1. **卡背改成按 JSON 清单渲染**：默认清单 11 块（TITLE / PHONETIC / DIVIDER / MEANING / MEMORY / COLLOCATIONS / EXAMPLE / PHRASES / SYNONYMS / REL_WORDS / EF_STATS）。默认清单下与 1.4.1 **逐像素一致**（front 0 px；back 173 px / 0.0072%，最大通道差 4/255，纯抗锯齿）。
2. **电脑端可视化编辑器**：`tools/cardback-editor/`（零依赖原生 JS + Python3 标准库服务）。浏览器里拖块调顺序、开关显隐、改标题、选 6 种语义色、字号 S/M/L，右侧实时预览，一键发布到手机。
3. **手机上两个入口**：词库页「当前词书：六级记忆卡」行最右侧的 refresh 图标；我的页「卡背结构地址」输入框（可填 `IP:8765`，也接受完整 URL —— **这就是给「在外面远程操控电脑改结构」留的接口**）。
4. **容错四级**：电脑不在 → 提示「电脑没连上…」且卡背沿用旧的；非法清单 → 手机端拒收、退回上一版；未知 type → 跳过并提示；失败重启仍是上一版。服务端保留历史版本，`/api/rollback` 一键回退。

### 验收状态（1.4.2）

- 独立测试 agent 按冻结规格跑完：**98 / 100**（A 30/30 · B 28/30 · C 20/20 · D 15/15 · E 5/5），K1–K5 否决项均未触发。报告 `docs/report-1.4.2-test.md`，证据 `docs/shots/test-1.4.2/`（`artifacts-test/` 是它的临时工作区，160 MB，已 gitignore）。
- 唯一失分项 **B6**（手机端缺 revision 闸门）已修：`CardBackRepository` 现在「revision 没变大且内容没变」就不写盘、不重渲染，toast 改口「卡背结构已是最新：第 N 版，N 块」；顺手给拉取加了 `Connection: close` + 失败重试一次，压掉测试中约 7% 的偶发「电脑没连上」。
- 复测（模拟器）：同内容连点两次 → 配置 mtime 不变、toast「已是最新」；发布新版 → mtime 变、toast「已更新」；rollback 回默认 → 与 1.4.1 基线 front 0 px / back 0.0112%（max 4）。
- **评分 agent 独立复核已完成：100 / 100**（A 30 · B 30 · C 20 · D 15 · E 5，K1–K5 全未触发）。报告 `docs/report-1.4.2-score.md`，证据 `docs/shots/score-1.4.2/`（65 份）。它自己重建 APK（sha256 `2f7101da…0c661`）、自己起服务、自己截图，**没读**测试报告与测试证据；规格 sha256 开工收工各验一次一致。

> 评分方另记了 3 条值得留意的观察（不扣分）：① 服务端 `published/` 若被外部重建，revision 会退回 1，手机端要等它重新超过本地缓存才会应用（22:32 那次已查明是**我方**在 8768 端口做 `server.py` 回归测试后删目录所致，不是 App 缺陷）；② 偶发一次「电脑没连上」而服务在监听（重试即好）；③ 暗色下 warning 色对比度 4.21:1，略低于 AA 的 4.5。

### 怎么用（用户视角）

双击 `tools/cardback-editor/启动编辑器.bat`（或双击 `dist/exe/卡背编辑器.exe` / 桌面快捷方式「卡背编辑器」，**不需要装 Python**）→ 浏览器开 `http://127.0.0.1:8765/` → 改 → 点发布 → 手机在我「我的」页填 `电脑IP:8765` 保存 → 回词库页点刷新图标。完整说明见 `README.md`「卡背编辑工具」一节。

### 还没做的

- **验收**：已完成（测试 98/100 → 修 B6 → 复测 → 独立评分 100/100，两次都用冻结规格 `docs/test-spec-1.4.2.md`，sha256 锁在同名 `.lock`）。
- **1.4.3**：重写按钮 + 模型下拉 + 折叠输入框 + 离线标记队列 + 电脑端改写服务（见 `docs/decisions-1.4.2.md`）。
- **动效参数**（820 / 90 / 220 / 72% / -1 / 1.0015）已写进 schema 与清单，**App 还没接管** —— 留到 1.4.3 或单独一步。
- **装真机**：等用户点头。

---

## 2. 目录地图

| 路径 | 是什么 |
|---|---|
| `app/src_repo/` | 安卓源码（**已删除嵌套 .git**，上游基线 c0a7fe0） |
| `app/src_repo/app/src/main/java/com/kaoyan/wordhelper/` | 主代码。1.4.2 新增 `data/model/CardBack.kt`（schema/枚举/`CardBackCodec`）、`data/model/CardBackDefaults.kt`（内置默认 11 块清单）、`data/repository/CardBackRepository.kt`（缓存/在线刷新/回退）；改 `ui/screen/LearningScreen.kt`（`CardBackBlocks` 按清单渲染）、`ui/screen/BookManageScreen.kt`（刷新图标）、`ui/screen/ProfileScreen.kt`（地址输入）、两个 ViewModel、`SettingsRepository.kt`（`cardBackUrl`）、`KaoyanWordApp.kt`（仓库装配 + bootstrap）、`AndroidManifest.xml`（`usesCleartextTraffic`） |
| `content/words/test20.json` | 20 词试验导入包（20,719 B） |
| `content/seed/cet6_cards.json` | **1.4 内置种子**（5228 词，4.70 MB，schema 同 test20 + version/book 元信息） |
| `tools/build_seed.py` | 由 `by-word/*.json` 重新生成上面这份种子（纯脚本，零模型） |
| `dist/english-app-1.3-t20.apk` | 1.3 版 APK（30,723,515 B，versionCode 2） |
| `dist/english-app-1.4-cet6.apk` | **1.4 版 APK（32,998,851 B，versionCode 3，内置 5228 词）** |
| `dist/english-app-1.4.2.apk` | **1.4.2 版 APK**（33,221,506 B，versionCode 5 / versionName 1.4.2，含卡背清单 + B6 修复） |
| `tools/cardback-editor/` | 1.4.2 电脑端编辑器：`index.html`/`styles.css`/`app.js`/`samples.js`（零依赖）+ `server.py`（Python3 标准库，8765）+ `启动编辑器.bat` + `build-exe.bat`（重打包 exe）；`published/` 是运行时数据（gitignore） |
| `dist/exe/卡背编辑器.exe` | **编辑器单文件 exe**（PyInstaller onefile，8.6 MB，免 Python，桌面有快捷方式）；`published/` 落在 exe 同级；不进 git，用 `build-exe.bat` 重建 |
| `docs/cardback-schema-1.4.2.md` | **卡背清单契约**（JSON 结构、11 种块、6 语义色、字号倍率、动效默认、6 个 HTTP 接口、容错分级） |
| `docs/test-spec-1.4.2.md` + `.lock` | **冻结的验收规格**（A 外观 30 / B 下发 30 / C 容错 20 / D 编辑器 15 / E 文档 5，K1–K5 否决项），sha256 锁文件 |
| `docs/testdata/cardback-*.json` | 验收用 5 个清单样本（valid-modern / valid-minimal / invalid-empty-blocks / invalid-schema / unknown-type） |
| `docs/shots/base-1.4.1/`、`docs/shots/new-1.4.2/` | 1.4.1 基线三屏 + 1.4.2 对照与各 toast 证据截图 |
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

### 1.4.2 的电脑端服务与模拟器坐标

```bash
cd "/d/deepseek harness/work_anki_enligish"
python tools/cardback-editor/server.py          # 0.0.0.0:8765，Ctrl+C 停
# 模拟器访问宿主机用 10.0.2.2；真机用电脑局域网 IP
curl -s http://127.0.0.1:8765/api/state
curl -X POST http://127.0.0.1:8765/api/publish -d @docs/testdata/cardback-valid-modern.json
curl -X POST http://127.0.0.1:8765/api/rollback -d '{"revision":1}'
```

- 停服务：`netstat -ano | grep :8765` 找 PID → **`cmd //c "taskkill /PID <pid> /F"`**（Git Bash 里直接 `taskkill //PID` 会被改写成无效参数）
- 模拟器底部导航中心 y=2208：学习 x=126 / 查词 501 / 词库 677 / 我的 953
- **词库页 refresh 图标的真实触摸中心是 (966, 544)**（图标画在 y≈458，按视觉位置点会点空）
- toast 抓拍：成功约 0.35 s 后截；服务关闭要等 5 s OkHttp 超时后再等 1–2 s。连拍 10 张再按 toast 区域 `y1880:2080,x150:930` 的均值/标准差挑帧最稳
- 模拟器时区是 **UTC**（状态栏 12:xx = 本机 20:xx），别拿它验「今天」

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

**版本号铁律**：每次发版 `versionCode` 必须 +1（1.4→3、1.4.1preview→4、**1.4.2→5，当前 5**，下次 1.4.3 用 6）；**修一个 bug 不许连跳版本号**——内部验证包沿用同号覆盖重装，只有用户要留档时才升。DB 版本当前 14（1.4.2 没动 schema），下次改表必须 15。

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
2. **【1.4.2 · ✅ 已交付，等验收】**卡背可下发 + 电脑端编辑工具
   - ① 卡背已从「写死在 `WordCard` 里」改成「读 JSON 清单渲染」（块列表 + 顺序 + 显隐 + 标题 + 6 语义色 + 字号 S/M/L；动效字段在 schema 里但 App 未接管）
   - ② 电脑端编辑器 `tools/cardback-editor/`（左组件库 / 中画布拖排序 / 右实时预览 / 底部发布到手机），已写进 `README.md`
   - ③ 词库页「当前词书」行最右侧 Material refresh 图标 → 拉新结构；我的页可填地址
   - ④ 四级容错：保留上一版、服务端历史 + `/api/rollback`、未知块跳过并提示、非法清单拒收，**不会让卡片变空**
   - ⑤ 结构只到「段落级」，不做自由绝对定位；新按钮的行为预置在代码里
   - ⑥ 待办：跑测试 agent + 评分 agent（规格 `docs/test-spec-1.4.2.md` 已冻结）；动效接管 → 1.4.3
   - **1.4.3**：重写按钮 + 模型下拉 + 离线标记队列 + 电脑端改写服务
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

1. **先读本文件 + [`docs/decisions-1.4.2.md`](docs/decisions-1.4.2.md)**，再跑 `git log --oneline -3`、`git status` 确认工作区干净。
2. **别急着改代码**：用户红线是「没被叫就不许动手」。第一句先问他要做什么，只有他说「开始/改」才动。
3. **真机**：`10AF941PZJ00AKH`，已可无线接（`adb connect 10.103.216.69:5555`，见 §4）。但默认仍先在模拟器 `mf` 上验；真机要动屏幕之前说一声。
4. **接手时的状态**：1.4.1preview 已交付（评分 100/100）；**1.4.2 已开发完成、已出包并只在模拟器验过**，验收规格冻结在 `docs/test-spec-1.4.2.md`（sha256 见 `.lock`，执行方不得改）。按用户要求要跑「测试 agent → 评分 agent」两轮独立复核，之后才装真机。左滑易误触用户明确说「先不修」，别再主动提。
