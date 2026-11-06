# 不爱背单词 1.4.1 「卡片翻面手势改造」验收测试报告

> 评分依据：`docs/test-spec-1.4.1.md`（冻结版，sha256 `186e53568bf88f7d07365afbd341202d72c74f31e3ebf858ef133223fcd56e50`）
> 被测对象：`com.kaoyan.wordhelper` versionCode 3 / versionName "1.4"（debug，已安装，未重装）
> 测试时间：2026-11-06 18:22 – 18:42（主机时间，UTC+8）；设备时间比主机慢 8 小时（GMT）
> 评分员：独立测试 agent（全程未修改规格、未修改被测代码、未改 probe.sh 的判定行为）

---

## 1. 被测对象、设备与规格校验

### 1.1 设备与包信息

| 项 | 实测值 |
|---|---|
| 设备 | `emulator-5554`，product/model `sdk_gphone64_x86_64`，state `device` |
| 屏幕 | `Physical size: 1080x2340`，`Physical density: 440` |
| 包名 | `com.kaoyan.wordhelper` |
| versionCode / versionName | `3` / `1.4` |
| firstInstallTime | `2026-11-06 05:34:46`（设备时间） |
| lastUpdateTime | `2026-11-06 10:19:19`（设备时间，测试全程未变，证明未重装） |
| 进程 pid | `9836`（E2 全程未变） |
| 起始界面 | 学习页，认词模式，正面卡，词条 `influence`，进度 `1/10` |

采集命令：

```bash
export MSYS2_ARG_CONV_EXCL='*'
ADB="/c/Program Files/ASUS/GlideX/adb.exe"
"$ADB" devices -l
"$ADB" -s emulator-5554 shell dumpsys package com.kaoyan.wordhelper | grep -E "versionCode|versionName|InstallTime|UpdateTime"
"$ADB" -s emulator-5554 shell wm size; "$ADB" -s emulator-5554 shell wm density
```

### 1.2 规格完整性校验（测试开始前 / 结束后各一次）

```text
# 开始前 18:22:2x
186e53568bf88f7d07365afbd341202d72c74f31e3ebf858ef133223fcd56e50 *docs/test-spec-1.4.1.md
# lock 文件内容
186e53568bf88f7d07365afbd341202d72c74f31e3ebf858ef133223fcd56e50 *docs/test-spec-1.4.1.md

# 结束后 18:42
186e53568bf88f7d07365afbd341202d72c74f31e3ebf858ef133223fcd56e50 *docs/test-spec-1.4.1.md
```

**两次一致，与冻结值相同。** 规格文件与 lock 文件均未被修改。

`docs/verify/probe.sh` 的使用前后 sha256 一致（`6e66de5e17b0f541e822dedec10ff51057395ba417f24117ad5f36adbf3ee3b4`），本次只调用、未修改其任何行；所有判定均由本报告按规格口径手工给出，脚本不参与打分。

### 1.3 测试方式与工具

- 状态判定一律用可访问性树，未用肉眼看截图猜：`uiautomator dump` + 按 §1 的三段式口径（先判正面标志，再判 6 个背面区块标志之一，否则 UNKNOWN 重来）。
- 因为 `probe.sh state` 只输出 `FRONT/BACK/UNKNOWN`，为逐条记录证据，额外写了只读解析脚本 `.tmp/verify/parse.py`（读同一个 `_state.xml`，不改 dump 逻辑、不改判定口径），输出 `STATE | WORD | PROG | 可见背面区块`。`probe.sh` 报错时未出现，未触发"绕过"分支。
- 每条操作后按规格等待（F1/F2/F3/F4/F7/F8 为 1200ms，F6 为 1500ms）再 dump。

---

## 2. 逐项结果总表

| 项号 | 判定标准（一句话） | 实测次数 | 通过次数 | 结论 | 证据 |
|---|---|---|---|---|---|
| **F1** | 正面 tap(540,1056) 后 1200ms = 背面 | 5 轮 | 5 | **通过** | `.tmp/verify/log_f1f2.txt`，`.tmp/verify/00_initial.png` |
| **F2** | 背面 tap(540,1056) 后 1200ms = 正面 | 5 轮 | 5 | **通过** | `.tmp/verify/log_f1f2.txt` |
| **F3** | 背面上下滑 5+5 次后仍为背面 | 3 轮共 30 次滑动（2 轮官方口径 + 1 轮逐次监控） | 3/3 轮 | **通过** | `.tmp/verify/log_f3_official.txt`，`log_f3_monitor.txt` |
| **F4** | 背面 120ms 上滑后立刻 tap，5 轮全部回正面 | 5 轮 | 5 | **通过** | `.tmp/verify/log_f4.txt`，`f4_r1..r5_after.png` |
| **F5** | 按住态 vs 静止态：mean_diff < 2.5 且 pct_gt6 < 1% | 正面 3 次 + 背面 1 次 + 多点取样 3 帧 | 全部 | **通过** | `f5_front_1..3_*.png`，`f5_back_1_*.png`，`f5m_*.png`，`log_f5.txt`，`log_f5_multi.txt` |
| **F6** | 背面点星星(915,499) 后 1500ms 仍为背面 | 3 轮 | 3 | **通过** | `.tmp/verify/log_f6.txt` |
| **F7** | 正面左滑后词条或进度变化，且不是翻面 | 3 轮 | 3 | **通过** | `.tmp/verify/log_f7.txt`，`f7_r1..r3.png` |
| **F8** | 背面滑到底再 tap 后 1200ms = 正面 | 3 轮 | 3 | **通过** | `.tmp/verify/log_f8.txt`，`f8_r*_bottom.png` |
| **E1** | 翻面无纯黑/纯白帧、无半张空白帧、旋转方向一致 | 4 次录制 / 45 帧 | 未发现异常 | **通过（带覆盖度保留）** | `.tmp/verify/e1.mp4`，`e1b/c/d.mp4`，`e1_contact.png` |
| **E2** | 6 连点不 ANR/闪退，之后单击仍能翻面 | 3 轮 | 3 | **通过** | `.tmp/verify/log_e2_more.txt`，`e2_after_6taps.png` |

---

## 3. 逐项详细观察

### F1 正面轻点翻到背面（5/5 通过）

操作：正面下 `input tap 540 1056`，等待 1200ms，dump。

```text
[18:23:55] F1 round1: before=[FRONT | influence | 1/10 | ] after=[BACK |  | 1/10 | 常用搭配] -> PASS
[18:24:13] F1 round2: before=[FRONT | influence | 1/10 | ] after=[BACK |  | 1/10 | 常用搭配] -> PASS
[18:24:31] F1 round3: before=[FRONT | influence | 1/10 | ] after=[BACK |  | 1/10 | 常用搭配] -> PASS
[18:24:49] F1 round4: before=[FRONT | influence | 1/10 | ] after=[BACK |  | 1/10 | 常用搭配] -> PASS
[18:25:07] F1 round5: before=[FRONT | influence | 1/10 | ] after=[BACK |  | 1/10 | 常用搭配] -> PASS
```

判定：每轮 tap 后状态由 `FRONT` 变为 `BACK`（背面标志命中「常用搭配」），5/5 通过。
备注：dump 时背面处在**上次滚动位置**（不是顶部），故只命中「常用搭配」；这符合 §1 的多标志口径，不影响判定。

### F2 背面轻点翻回正面（5/5 通过）

同一脚本的交替轮次，背面 tap 同点后 1200ms：

```text
[18:24:04] F2 round1: before=[BACK |  | 1/10 | 常用搭配] after=[FRONT | influence | 1/10 | ] -> PASS
[18:24:22] F2 round2: ... -> PASS
[18:24:40] F2 round3: ... -> PASS
[18:24:58] F2 round4: ... -> PASS
[18:25:16] F2 round5: ... -> PASS
```

5/5 通过。

### F3 卡背上下滑动不翻面（3/3 轮通过，共 30 次滑动）

操作：背面下 `input swipe 540 1450 540 780 300`（上滑）×5，`input swipe 540 900 540 1570 300`（下滑）×5，间隔 600ms；每次跑前把卡背滚回顶部作为统一起点。

```text
[18:26:30] F3[official] run1 start: BACK | influence | 1/10 | 记忆思路
[18:26:47] F3[official] run1 final=[BACK | influence | 1/10 | 记忆思路] -> PASS
[18:27:02] F3[official] run2 start: BACK | influence | 1/10 | 记忆思路
[18:27:20] F3[official] run2 final=[BACK | influence | 1/10 | 记忆思路] -> PASS
```

另跑一轮「逐次监控」（每滑一次 dump 一次，代价是间隔被 dump 拉长，仅用于发现"中途是否曾翻面"）：

```text
up#1..up#5  -> BACK（区块从「记忆思路,常用搭配」滚到只剩「常用搭配」）
down#1..down#5 -> BACK（滚回「记忆思路」）
final -> BACK  PASS
```

说明：官方口径 10 次滑动后必然停在背面；监控口径证明 10 次**每一次之后**都是背面，没有出现"翻过去又翻回来"的中途翻转。
注：规格未规定 F3 的滑动时长，本次统一用 300ms（F4 才规定 120ms）。

### F4 卡背滑动后立刻点击仍能翻回正面（5/5 通过）

操作（严格按 §2 实施细则，同一条设备端 shell 连发，中间无 dump/截图/sleep）：

```bash
adb -s emulator-5554 shell "input swipe 540 1450 540 780 120; input tap 540 1056"
# 等 1200ms 后 dump
```

```text
[18:29:02] F4 round1 before=[BACK | influence | 1/10 | 记忆思路]
[18:29:08] F4 round1 after=[FRONT | influence | 1/10 | ] -> PASS
[18:29:29] F4 round2 ... -> PASS
[18:29:56] F4 round3 ... -> PASS
[18:30:23] F4 round4 ... -> PASS
[18:30:49] F4 round5 ... -> PASS
[18:30:56] F4 SUMMARY pass=5 fail=0
```

间隔可信度：为确认"≤250ms"这条真的被满足，实测了设备端 `input` 进程启动开销（`date +%s%3N; input; date +%s%3N`）：

```text
input_startup_ms=59 / 57 / 69 / 69 / 68
```

即 swipe 结束到 tap 事件下发之间只隔一次 shell 串行 fork + `input` 启动，估计 ≤150ms，**满足 ≤250ms**。

### F5 按压全程无水波纹（通过）

官方取证方式（规格 §3 原样，`probe.sh hold` = 静止截图 → 原地按住 1500ms → 第 600ms 截图 → 松手）：

```bash
bash docs/verify/probe.sh hold 540 1056 1500 f5_front_1
bash docs/verify/probe.sh f5diff f5_front_1_rest.png f5_front_1_press.png
```

| 取样 | 场景 | mean_diff | pct_gt6 | 判定 |
|---|---|---|---|---|
| #1 | 正面 | **0.00** | **0.00%** | 通过 |
| #2 | 正面 | **0.00** | **0.00%** | 通过 |
| #3 | 正面 | **0.00** | **0.00%** | 通过 |
| #4（补充） | 背面 | **0.00** | **0.00%** | 通过 |
| 规格给出的修复前基线 | — | 19.71 | 98.13% | — |

加固取样（一次 2000ms 按住内连拍 3 张，覆盖按压早期/中期/末期，避免"涟漪只在很短时间内出现"被 600ms 单点漏掉）：

```text
[00:0] state=FRONT
p1: mean_diff=0.00 pct_gt6=0.00% max=0
p2: mean_diff=0.00 pct_gt6=0.00% max=0
p3: mean_diff=0.00 pct_gt6=0.00% max=0
松手后: mean_diff=10.88 pct_gt6=8.60% max=206   ← 卡片翻到背面，说明取样链路正常
```

**方法有效性正对照**（关键，防止"测不到"被误当成"没有"）：同一套"按住 1500ms、第 600ms 截图"的取样，用在卡片右上角星标按钮上：

```text
星标矩形 (882,466)-(948,532): mean_diff=16.69 pct_gt6=84.89% max=20
整卡矩形 (44,378)-(1036,1735): mean_diff=0.12  pct_gt6=0.65%  max=20
```

即：取样方法**能**检出涟漪（星标自己的按压反馈仍在，16.69/84.89%），而卡片本体在同一时刻是 0.00 —— 说明"卡片无水波纹"是真实结果，不是取样失灵。
（星标涟漪不属于本次改造范围，规格只要求卡片无水波纹；仅记录，不影响 F5 判定。）

### F6 点卡内控件不连带翻面（3/3 通过）

操作：背面下 `input tap 915 499`（星星中心），等 1500ms，dump。

```text
[18:33:01] F6 round1 before: BACK |  | 1/10 | 记忆思路,常用搭配
[18:33:07] F6 round1 after1500ms: BACK |  | 1/10 | 记忆思路,常用搭配
[18:33:10] F6 round2 ... -> BACK
[18:33:19] F6 round3 ... -> BACK
```

3/3 仍为背面。全程星星只切换收藏态（`content-desc` 在 `加入生词本` ↔ `已加入生词本` 之间切换），未触发翻面；测试结束时已把收藏态恢复为初始的「加入生词本」。

### F7 正面左右滑功能未被改坏（3/3 通过）

操作：正面下 `input swipe 700 1056 200 1056 250`，等 1200ms，dump。

```text
[18:33:43] F7 round1 before: FRONT | influence | 1/10 | 
[18:33:48] F7 round1 after:  FRONT | prevent   | 2/10 |   -> 词条与进度都变，未翻面
[18:33:53] F7 round2 before: FRONT | prevent   | 2/10 |
[18:33:59] F7 round2 after:  FRONT | promote   | 3/10 |
[18:34:04] F7 round3 before: FRONT | promote   | 3/10 |
[18:34:09] F7 round3 after:  FRONT | reduce    | 4/10 |
```

3/3 通过：每次左滑后词条变化（`influence → prevent → promote → reduce`）且进度前移（`1/10 → 2/10 → 3/10 → 4/10`），三次都停在 `FRONT`，没有翻面。

### F8 卡背滚到底部再点击仍能翻回正面（3/3 通过）

操作：背面 → `input swipe 540 1450 540 780 300` ×3 → 等 800ms → `input tap 540 1056` → 等 1200ms → dump。

```text
[18:34:37] F8 round1 back_state=BACK | reduce | 4/10 | 记忆思路,常用搭配
[18:34:48] F8 round1 after=[FRONT | reduce | 4/10 | ] -> PASS
[18:34:57] F8 round2 ... -> PASS
[18:35:17] F8 round3 ... -> PASS
[18:35:28] F8 SUMMARY pass=3 fail=0
```

"到底"的验证（规格只说"连续上滑 3 次到底"，未定义怎么判到底）：滚 3 次后截图，再滚 3 次再截图，比较卡片区域像素：

```text
3 次 vs 6 次: mean_diff=0.00 pct_gt6=0.00% max=0
```

说明第 3 次时**确实已经到底**（再滑无任何位移），F8 的前置条件成立。
证据：`.tmp/verify/f8_bottom_3swipes.png`、`f8_bottom_6swipes.png`（两者像素完全相同）。

### E1 翻面动画连贯性（未发现异常，10 分但有覆盖度保留）

方法：`screenrecord --time-limit 6 --size 540x1170 --bit-rate 4000000`，录一次 F1+F2（正面 → 背面 → 正面），ffmpeg 抽帧检查。

主录制 `.tmp/verify/e1.mp4`：3.553s / 11 帧（r_frame_rate 35/1，实际因丢帧约 3fps）。逐帧数据：

| 帧 | 时间(s) | 状态 | 卡片区 std | 纯黑占比 | 纯白占比 |
|---|---|---|---|---|---|
| f01 | 0.000 | 正面 | 21.5 | 0.00% | 29.98% |
| f02 | 0.923 | 正面 | 21.5 | 0.00% | 29.97% |
| f03 | 1.009 | 正面 | 21.5 | 0.00% | 29.99% |
| f04 | 1.067 | 正面 | 20.1 | 0.00% | 29.97% |
| **f05** | **1.326** | **中间帧（正面→背面）** | 27.8 | 0.00% | 29.85% |
| f06 | 1.384 | 背面 | 33.4 | 0.00% | 30.21% |
| f07 | 3.126 | 背面 | 33.7 | 0.00% | 30.59% |
| f08 | 3.183 | 背面 | 33.6 | 0.00% | 30.67% |
| **f09** | **3.302** | **中间帧（背面→正面）** | 11.5 | 0.00% | 30.76% |
| f10 | 3.440 | 正面 | 21.2 | 0.00% | 32.53% |
| f11 | 3.497 | 正面 | 21.5 | 0.00% | 32.61% |

逐条对照规格三条判据：

1. **无纯黑/纯白帧**：11 帧纯黑占比均为 `0.00%`；纯白占比稳定在 29.85%–32.61%（这是卡片白底的面积，正反面静止帧同样在 ~30%），**没有出现整帧白屏/黑屏**。
2. **正反面之间无「半张空白」帧**：f05 是透视倾斜、内容完整可辨（记忆思路②③、常用搭配、例句都在，只是被压扁、顶边右高左低）；f09 是接近 90° 的"边朝观众"帧——卡片内容收缩成卡片垂直中轴附近的一条窄带（暗色像素列 131/496），属于 3D 翻转的必然中间态，不是渲染空白（它的白色底面积与静止帧相同，没有出现"半张没画出来"）。
3. **旋转方向一致**：两次翻面的中间帧都表现为**绕竖直轴的横向压缩、以卡片水平中心为轴**（f05 内容中心 ≈ x255，卡片中心 x270；f09 窄带居中 x≈240–310），未观察到镜像/反向翻转。

**覆盖度保留**：本环境合成帧率太低，3.553s 只有 11 帧，300ms 的翻面动画每次**只落到 1 个中间帧**。为补足，另外录了 3 次（e1b/e1c/e1d，各 11–12 帧），帧序与主录制**完全一致**（同样的正面 4 帧 → 1 个中间帧 → 背面 3 帧 → 1 个中间帧 → 正面），45 帧合计仍只有 2 个中间帧/方向。因此"0→90°→180°"的逐帧过渡**无法完整观察到**，第 3 条判据只能做到"两支中间帧形态一致、未见反向"。按规格"出现任一异常才 0 分"的口径，在可观察范围内未发现异常，给 10 分，但证据强度受环境限制（见 §6）。

证据：`.tmp/verify/e1_contact.png`（11 帧卡片区拼图）、`.tmp/verify/e1.mp4`、`e1b.mp4`、`e1c.mp4`、`e1d.mp4`。

### E2 快速连点不卡死、不错位（3/3 通过）

操作：设备端一条命令内连点 6 次、间隔 300ms（`for i in 1..6; do input tap 540 1056; sleep 0.3; done`），再等 2s，dump；随后单次 tap 验证仍能翻面。

| 轮次 | 连点前 | 6 连点 + 2s 后 | 之后单击 | pid | 结论 |
|---|---|---|---|---|---|
| 1 | FRONT \| reduce \| 4/10 | FRONT \| reduce \| 4/10 | → BACK | 9836 → 9836 | 通过 |
| 2 | BACK \| \| 4/10 | BACK \| \| 4/10 | → FRONT | 9836 | 通过 |
| 3 | FRONT \| reduce \| 4/10 | FRONT \| reduce \| 4/10 | → BACK | 9836 | 通过 |

- 6 次连点后都停在正面或背面（奇数/偶数次一致地对齐，未出现"卡在中间态"或 `UNKNOWN`）。
- 进程 pid 全程 `9836` 未变，无重启、无闪退。
- `logcat -b crash` 空；`logcat` 无 `ANR in`/`FATAL EXCEPTION`；`/data/anr/` 与 dropbox 中**测试时段（10:22 之后，设备时间）没有新增 ANR**（历史 ANR 见 §6）。
- 每次连点后单击仍能正常翻面。

证据：`.tmp/verify/log_e2_more.txt`、`.tmp/verify/e2_after_6taps.png`。

---

## 4. 打分

### 4.1 功能项（80 分，每项 10 分，全或无）

| 项 | 得分 | 理由 |
|---|---|---|
| F1 正面→背面 | **10 / 10** | 5/5 轮 `FRONT` → `BACK` |
| F2 背面→正面 | **10 / 10** | 5/5 轮 `BACK` → `FRONT` |
| F3 上下滑不翻面 | **10 / 10** | 官方口径 2 轮 ×10 次滑动，final 均为 `BACK`；监控口径 10 次逐步 dump 全部 `BACK`，无中途翻转 |
| F4 滑动后立刻点击 | **10 / 10** | 5/5 轮设备端连发 `swipe(120ms)+tap` 后回到 `FRONT`；实测 `input` 启动 57–69ms，间隔满足 ≤250ms |
| F5 无水波纹 | **10 / 10** | 正面 3 次、背面 1 次、多点加固 3 帧，全部 `mean_diff=0.00`、`pct_gt6=0.00%`，远优于阈值 2.5 / 1%；正对照证明取样方法有效 |
| F6 点星标不翻面 | **10 / 10** | 3/3 轮点星标后 1500ms 仍 `BACK` |
| F7 左右滑未改坏 | **10 / 10** | 3/3 轮词条与进度均变化，且均未翻面 |
| F8 滚到底再点击 | **10 / 10** | 3/3 轮回 `FRONT`；已证明 3 次上滑确实到底（再滑像素 0 变化） |
| **功能小计** | **80 / 80** | |

### 4.2 体验项（20 分，每项 10 分）

| 项 | 得分 | 理由 |
|---|---|---|
| E1 翻面动画连贯 | **10 / 10** | 45 帧内无纯黑/纯白帧、无半张空白帧，中间帧为正常 3D 翻转透视；旋转方向未见反向。扣分条件（出现任一异常）未触发。**注意：受模拟器合成帧率限制（~3fps，每次翻面仅 1 个中间帧），第 3 条判据只能间接判断，见 §6** |
| E2 快速连点 | **10 / 10** | 3/3 轮：无 ANR、无闪退、pid 不变、状态停在正面或背面、之后单击仍正常 |
| **体验小计** | **20 / 20** | |

### 4.3 帧率数据（按规格 §4，仅记录、不评分）

`dumpsys gfxinfo com.kaoyan.wordhelper` 原始数据（完整文件 `.tmp/verify/gfxinfo.txt`）：

```text
Total frames rendered: 1614
Janky frames: 1614 (100.00%)
50th percentile: 150ms
90th percentile: 250ms
95th percentile: 300ms
99th percentile: 450ms
Number Missed Vsync: 1187
Number Slow UI thread: 1489
Number Slow issue draw commands: 1614
Number Frame deadline missed: 1614
50th/90th/95th/99th gpu percentile: 4950ms
```

**环境不可用，仅记录**：headless + swiftshader 软件渲染下 100% janky、50 分位 150ms，与规格描述一致。按规格**未据此加减分**。

---

## 5. 结论

| 项 | 结果 |
|---|---|
| 功能分（F1–F8） | **80 / 80** |
| 体验分（E1–E2） | **20 / 20** |
| **总分** | **100 / 100** |
| **功能判定** | **功能合格**（F1–F8 全部 10 分，无 0 分项，未触发一票否决） |
| 一票否决条件 | 未触发 |

**总体结论：功能合格，总分 100 分。**

---

## 6. 遗留问题与不确定点

### 6.1 规格本身的含糊之处（只报告，未自行放宽）

1. **F3 / F8 未规定滑动时长**：规格只给坐标与次数，F4 才明确 120ms。本次 F3、F8 统一用 `300ms`（相当于常规快滑）。若换更长/更短的滑动时长，结果可能不同，规格应明确。
2. **F8 的"到底"没有定义判定方法**：本报告用"再滑 3 次像素零变化"证明已到底；规格未说明执行方应如何自证，属于可复现性缺口。
3. **F5 未规定测正面还是背面、也没规定重复次数**：本次正面 3 次 + 背面 1 次 + 一次多帧按住，结论一致（0.00）。若要求"任一面有波纹即 0 分"，本项仍通过。
4. **E1 的"抽帧到 30fps"在本环境不可达**：录制实际输出 3.553s/11 帧（约 3fps），且每次翻面只产生 1 个中间帧。规格对 gfxinfo 明确写了"环境不可用仅记录"，但对 E1 没有同类豁免；本次按"无异常即通过"处理，并在此标注**证据强度不足**。这是本报告唯一一处判定依赖解释的地方，建议规格补充 E1 在低帧率环境下的取证口径（例如允许用 `screenrecord` 多轮取样或改用逐帧 dump）。
5. **F7 示例写的是 `1/11`**，实际牌堆为 `1/10`（本机进度显示 `1/10`）。仅示例与现场不一致，不影响判定。
6. **E2 未规定连点由主机端还是设备端下发**：主机端发 `input tap` 会引入 ~100–200ms 的 adb 开销，间隔远大于 300ms。本次用设备端 shell 循环（`input tap; sleep 0.3`）以保证间隔真实。
7. **§2 未定义"部分得分"**：规格只有 10 分/0 分两种给分方式（"每项 10 分" + "任一 0 分即不合格"），本次严格按全或无执行，没有做半分。

### 6.2 环境限制

- **模拟器合成帧率极低**（gfxinfo 100% janky、50 分位 150ms；录屏约 3fps），导致 E1 无法做严格的 0→90°→180° 逐帧比对。功能项不受影响（状态判定走可访问性树，不依赖帧率）。
- 设备时间比主机慢 8 小时（GMT vs UTC+8），本报告中的 dropbox/anr 时间戳均为设备时间。

### 6.3 观察到的、不影响本次评分的事实

1. **星标按钮仍有 Material 涟漪**：正对照测得星标矩形 `mean_diff=16.69 / 84.89%`。规格 F5 只针对"卡片点击翻面"的水波纹，星标是卡内另一个控件，故不扣分；但如果产品目标是"整个卡片区域都不要涟漪"，这一处属于未覆盖。
2. **卡片白底在翻转时看起来不跟着转**：中间帧里"未安排"胶囊和星标位置不变、只有正文区域在旋转（说明翻转动画作用在正文容器而非整张卡）。规格 E1 未对此提要求，故未扣分，仅记录。
3. **卡背滚动位置跨翻面保留**：翻回正面再翻回背面，会停在上次滚动位置（不是回到顶部）。这会影响测试起点的可复现性（本次在 F3/F4 前手动滚回顶部）。规格未规定这一点，可能是设计如此，也可能是待确认项。
4. **测试期间源码目录出现一次未预期的 mtime 变化**：`app/src_repo/app/src/main/java/com/kaoyan/wordhelper/ui/screen/LearningScreen.kt` 的 mtime 为 `2026-11-06 18:22:36`（本 agent 会话开始于 18:22:18 之后 18 秒）。**本 agent 全程未对 `app/src_repo` 执行任何写操作**（只读 `git status` / `ls`）。该文件与已安装 APK 的关系无法确认：APK 的 `lastUpdateTime` 为设备时间 10:19:19（= 主机 18:19:19），早于该 mtime，因此**当前源码可能比已装包新**。本次评分只针对**已安装的包**，未重装、未清数据，被测对象一致性不受影响；但"源码 ↔ 安装包"是否一一对应无法由本次测试证明。
5. **历史 ANR 记录（测试前）**：dropbox 中有 7 条 `data_app_anr`（设备时间 03:45:35 / 09:57:51 / 10:05:41 / 10:08:54 / 10:10:59 / 10:13:28 / 10:19:54），均早于本次测试开始（10:22），其中至少一条的堆栈落在 `WordbookFullLoader.buildPhrases` / `AppNavigation.kt:118` 启动路径上。**测试时段内没有新增 ANR / crash**（E2 三轮 6 连点期间同样没有），故不影响 E2 判定；但这些历史记录说明该 App 在慢速软件渲染环境下启动阶段有 ANR 风险，值得单独立项排查。

---

## 附录 A：本次执行用到的关键命令

```bash
export MSYS2_ARG_CONV_EXCL='*'
ADB="/c/Program Files/ASUS/GlideX/adb.exe"

# 状态（规格 §1 口径，probe.sh 只做 dump+重试）
bash docs/verify/probe.sh state

# 点击 / 滑动
"$ADB" -s emulator-5554 shell input tap 540 1056
"$ADB" -s emulator-5554 shell input swipe 540 1450 540 780 300

# F4：设备端连发（与规格 §2 实施细则一致）
"$ADB" -s emulator-5554 shell "input swipe 540 1450 540 780 120; input tap 540 1056"

# F5
bash docs/verify/probe.sh hold 540 1056 1500 f5_front_1
bash docs/verify/probe.sh f5diff f5_front_1_rest.png f5_front_1_press.png

# E1
"$ADB" -s emulator-5554 shell screenrecord --time-limit 6 --size 540x1170 --bit-rate 4000000 /sdcard/e1.mp4
ffmpeg -y -i .tmp/verify/e1.mp4 -vsync 0 .tmp/verify/e1_frames/f_%03d.png

# E2：设备端 6 连点
"$ADB" -s emulator-5554 shell 'for i in 1 2 3 4 5 6; do input tap 540 1056; sleep 0.3; done'

# 帧率（仅记录）
"$ADB" -s emulator-5554 shell dumpsys gfxinfo com.kaoyan.wordhelper
```

## 附录 B：证据文件清单（均在 `.tmp/verify/`）

| 文件 | 内容 |
|---|---|
| `log_f1f2.txt` / `log_f3_official.txt` / `log_f3_monitor.txt` / `log_f4.txt` / `log_f5.txt` / `log_f5_multi.txt` / `log_f6.txt` / `log_f7.txt` / `log_f8.txt` / `log_e1.txt` / `log_e1_more.txt` / `log_e2_more.txt` | 各项逐轮原始输出 |
| `00_initial.png` | 测试起始界面（正面，influence，1/10） |
| `01_back_top.png` / `02_back_top2.png` | 背面布局（滚动中 / 顶部） |
| `f4_r1..r5_after.png` | F4 每轮 tap 后的正面截图 |
| `f5_front_1..3_rest/press/after.png`、`f5_back_1_*.png` | F5 官方取证截图 |
| `f5m_rest/p1/p2/p3/after.png` | F5 加固：一次按住内多点取样 |
| `f5_star_ctrl_rest/press/after.png` | F5 正对照（星标按钮涟漪） |
| `f7_r1..r3.png` | F7 每轮左滑后截图 |
| `f8_r1..r3_bottom.png`、`f8_bottom_3swipes.png`、`f8_bottom_6swipes.png` | F8 底部状态与"已到底"证明 |
| `e1.mp4` / `e1b.mp4` / `e1c.mp4` / `e1d.mp4`、`e1_contact.png`、`e1_frames/`、`e1all/` | E1 录屏与抽帧 |
| `e2_after_6taps.png` | E2 连点后截图 |
| `gfxinfo.txt` | gfxinfo 原始输出（仅记录） |
| `parse.py` / `st.sh` / `diffrect.py` / `run_*.sh` | 只读解析与执行脚本（不参与判定） |

> 注：`.tmp/verify/` 下另有 `base_*`、`fix1_*`、`hold2_*`、`smoke1.png`、`star_before/after.png`、`flip.mp4`、`s0/s1.png` 等文件，时间戳为 17:52–18:20，属于冻结规格之前的预检产物，**不是本次测试的证据**，未在本报告中引用。
