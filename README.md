# 不爱背单词 · 六级图文记忆版

在开源项目 [GuGuGuGun/english__app](https://github.com/GuGuGuGun/english__app)（Apache-2.0）基础上改造，
目标是把已经做好的「单词记忆思路 / 常用搭配」文本塞进卡片背面，先做纯文字、不做配图与动效。

- 上游基线：`c0a7fe0a9b518d06d41636f8f2364a0ab07e7e6b`（v1.2.2 之后，2026-03-24「feat: 修改部分表述」）
- 上游 LICENSE 原样保留在 `app/src_repo/LICENSE`，本仓库的改动汇总见文末「本仓库改了什么」


## 自测与调查题目

### A. 20 词自测要看什么

1. **思路长度**：三段（①②③）在一屏内读不读得下去，会不会太啰嗦
2. **搭配质量**：`increase by 20% —— 往上蹿了两成` 这种写法是不是你要的
3. **词性分行**：`v. …` / `n. …` 是否清楚，还有没有挤在一行的情况

### B. 本轮调查题目（墨墨背单词）

**卡片格式**
1. 一张卡片正面、反面分别展示哪些元素，顺序与版式是什么
2. 记忆思路以什么形态存在：自由文本，还是结构化的「词根 / 联想 / 例句」字段
3. 用户自己的笔记、例句、图片怎么进卡片，能不能批量导入（CSV / Anki / API）
4. 卡片内容从哪来：人工编辑、算法挑选、社区共享，还是模型生成

**同步机制**
1. 同步粒度：整包覆盖、增量合并，还是对象级；有没有版本号 / 时间戳 / 删除墓碑
2. 两端都改了同一张卡，冲突怎么判
3. 复习历史是否上传、能否导出、换设备怎么迁移
4. 客户端与服务端之间是什么接口与鉴权方式，有没有可参考的开源实现

> 报告产出：`docs/report-maimemo-card-format.md`、`docs/report-maimemo-sync.md`

## 目录结构

```
work_anki_enligish/
├─ app/
│  └─ src_repo/          安卓源码（Fork 自上游，改动见「本仓库改了什么」）
├─ content/
│  └─ words/             导入用的词条 JSON（每批一个文件，按日期或用途命名）
├─ media/                以后放图片（本版未使用）
├─ sync-service/         以后放电脑端同步服务（本版未使用）
├─ docs/                 设计说明、验收记录、截图
├─ _toolchain/           本机构建工具链（Gradle、签名、依赖缓存）——不进版本管理
└─ _recon/               调研期的临时产物（截图、比对脚本）
```

**硬盘卫生**：`_toolchain/` 大约 800 MB（Gradle 发行版 + 依赖缓存 + 签名），
`app/src_repo/app/build/` 是构建产物，两者都可以随时删除重建，不要往这两个目录里放源码或素材。

## 构建

本机没有安装系统级 Gradle 与 Android SDK 的环境变量，构建走自带工具链：

```bash
cd app/src_repo
export JAVA_HOME="/c/Users/ouquan/AppData/Local/Programs/Microsoft/jdk-17.0.10.7-hotspot"
export GRADLE_USER_HOME="D:/deepseek harness/work_anki_enligish/_toolchain/gradle-home"
"$GRADLE_USER_HOME/../gradle-8.9/bin/gradle" --no-daemon :app:assembleDebug
```

产物：`app/src_repo/app/build/outputs/apk/debug/app-debug.apk`

依赖走阿里云镜像（`_toolchain/gradle-home/init.d/mirrors.gradle`），
Gradle 发行版走腾讯云镜像，两者都不改项目源码。

## 覆盖安装（重要）

更新必须能直接覆盖安装，不许要求先卸载。三个条件都固定死了：

| 条件 | 做法 |
|---|---|
| 签名 | `_toolchain/signing/wordhelper.jks`（别名 `wordhelper`），**这把 key 不能换、不能丢** |
| 包名 | `com.kaoyan.wordhelper`，与官方版相同 |
| 版本号 | `versionCode` 与 `versionName` 同步维护（当前 `versionCode 4 / versionName 1.4.1preview`） |

数据库版本只升不降，每次升级都带迁移。当前 13 → 14（只新建 `tb_word_memory`，不动旧表）。

> 唯一的例外：官方版用的是作者的签名，与本项目不同，所以**从官方版切过来必须卸载一次**，
> 学习进度会清空。切过来之后，后续版本都能互相覆盖。

## 无线调试（adb over WiFi）

真机可以完全脱离 USB 线，**只要手机不重启就一直有效**。

```bash
# 首次需要 USB 连着（之后就不用了）
ADB="/c/Program Files/ASUS/GlideX/adb.exe"
export MSYS2_ARG_CONV_EXCL='*'
"$ADB" -s 10AF941PZJ00AKH tcpip 5555
"$ADB" connect 10.103.216.69:5555
"$ADB" devices                              # 应同时列出序列号与 IP:5555
"$ADB" -s 10.103.216.69:5555 shell echo OK  # 确认无线通道真能用，再拔线
```

- **手机真实 IP**：`adb shell ip addr show | grep 'inet '`，看 **wlan0** 那一行。
- **别信手机「无线调试」页面显示的 IP**：它可能是 `172.19.0.1`（`tun0` 的 VPN 虚拟网卡），电脑连过去只会超时。
- **持久性**：`service.adb.tcp.port=5555` 在手机重启前一直有效。重启后 adbd 退回 USB 模式，需要重新 `adb tcpip 5555`；不想插线就走系统「无线调试」+ 它显示的端口。
- **命门**：锁屏不影响连接，但 vivo 的省电策略可能掐 WiFi；换 WiFi 会换 IP（DHCP）。
- 本机 `adb mdns check` 报 `mdns daemon unavailable`，没有 mDNS 自动发现，IP 变了要手动重连。

## 导入测试词条

1. 把 `content/words/test20.json` 传到手机（微信/数据线/网盘都行）
2. App → 词库 → 右下角 **+** → 导入文件 → 选中该 JSON
3. 预览前 5 条确认无误 → 确认导入

导入永远新建一本词书，不会覆盖内置词库；新书会自动继承全局学习进度。

### JSON 格式

最小可用：

```json
[{"word": "abandon", "phonetic": "/əˈbændən/", "meaning": "v. 放弃", "example": "He abandoned the plan."}]
```

带记忆思路与搭配：

```json
[{
  "word": "abide",
  "phonetic": "/əˈbaɪd/",
  "meaning": "v. 遵守；容忍（abide by sth 遵守规则）",
  "example": "All members must abide by the rules.\n所有成员都得遵守规定。",
  "approach": ["① …", "② …", "③ …"],
  "collocations": ["abide by —— 遵守，按规矩来"]
}]
```

- `word` 是唯一必填项，缺字段会被静默跳过
- `approach` 也认 `derivation`；`collocations` 也认 `chunks`
- 换行用 `\n`，卡背会一行一条显示

## 本仓库改了什么

相对上游 v1.2：

1. 新增 `tb_word_memory`（`word_id` 主键 + 记忆思路 + 常用搭配），数据库 13 → 14
2. 导入解析器支持 `approach` / `collocations`
3. 卡背新增「记忆思路」「常用搭配」两个区块，释义按词性分行
4. `versionCode` / `versionName` 升到 2 / 1.3，签名固定

**刻意没做**：配图、动效、云同步、界面重做。

## 本版验证记录（模拟器 API 34）

| 环节 | 结果 |
|---|---|
| 导入 test20.json | 词书 3 本 → 4 本，累计词条 26526 → 26546，正好 +20 |
| 解析预览 | 释义、音标、例句全部正确带出 |
| 卡背 | 「记忆思路」①②③ 完整显示；「常用搭配」逐条显示 |
| 一词多义 | `increase` 显示为两行：`v. 增加；提高；增长` / `n. 增长；增加量` |
| 上下滑 | 卡背内容可滚动，能滚到最底部看见「常用搭配」与例句 |
| 左右滑 | 仅正面生效（上游设计：翻到背面后禁用滑动，避免误标）；背面上下滑不会误触发翻卡 |
| 覆盖安装 | `adb install -r` 升级后学习进度保留（1/19 未变） |

截图见 `docs/shots/`。

## 调试纪律（必须遵守）

1. **模拟器随便折腾，真机只在交付时碰，且每次碰之前先问一句。** 改代码、编译都不需要设备；要看手势/UI/布局的，一律先在模拟器（`emulator-5554`）上验，不拿用户的手机当调试台。
2. **不为一个 bug 连升版本号。** 内部验证包用同一个 `versionCode` 覆盖安装；只有用户明确要留档/发版时才升号。
3. **没被要求就不动手。** 先给「根因 + 改法」等一句「开始」，不要边猜边改边装机。
4. **一次讲清再改**：手势、触摸阈值这类问题先读代码定根因，改一版就要能改到位，不靠反复试错逼近。

## 版本号约定

- 当前交付 `versionCode 4 / versionName 1.4.1preview`（卡片翻面手势改造版，独立验收 100/100）
- 上一版 `versionCode 3 / versionName 1.4`（内置 5228 词种子，`dist/english-app-1.4-cet6.apk`）
- **下一次发版 `versionCode` +1（→5）**；内部验证包不要升号，同号覆盖安装即可
- 数据库版本 14；下次加表或加列必须是 15，并补一条迁移
