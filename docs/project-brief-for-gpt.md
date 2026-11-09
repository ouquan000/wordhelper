# english-app 动效改造 · 交给 GPT 的交接文档

> **读者**：一个能读取上传文件的自定义 GPT。
> **目的**：让它在**不改功能、不重构**的前提下，给出「动效/动画手感」的可落地修改建议。
> **边界**：本文档与随附源码片段是**全部输入**。不要假设能访问仓库、不要要求跑构建、不要建议换框架。
> **版本基线**：`versionName 1.4.7.1` / `versionCode 14` / Room DB `21`（2026-11-10）。

---

## 0. 给 GPT 的三句话约束（先读这段）

1. **只调手感**：可以改时长、缓动、位移/缩放幅度、插值方式、动画组合方式；**不改**布局结构、数据流、同步逻辑、文案与业务规则。
2. **一次只改一个动效**：明确说是哪一个（用本文档的 `M01`–`M33` 编号指代），给出 diff 级建议，不要产出整页重写。
3. **必须给验收标准**：改完后人眼/帧率怎么判断「成了」，以及要回归验证哪些既有交互。

---

## 1. 项目是什么

**一句话**：一个考研英语单词 App —— 单词卡正面记单词，背面是「记忆思路」（拆词/谐音/场景）与 AI 重写入口；用户可以左滑标记「太简单」、右滑加入生词本，并通过电脑端的 `cardback-editor`（本地 8765 端口 + Cloudflare Tunnel）同步卡背内容。

### 1.1 技术栈（事实，勿猜）

| 项 | 值 |
| --- | --- |
| 语言 / UI | Kotlin 2.0.0 · Jetpack Compose（**BOM 2024.05.00**，Compose UI 1.6.7 / Material3 1.2.1） |
| 构建 | AGP 8.4.0 · compileSdk/targetSdk 34 · **minSdk 26** · JVM 17 |
| 导航 | androidx.navigation:navigation-compose 2.7.7（单 Activity + NavHost） |
| 存储 | Room 2.6.1（`version = 21`，库名 `kaoyan_words.db`） |
| 主题 | 手写 `lightColorScheme` / `darkColorScheme`，**未启用 dynamic color**；`fontScale` 参数会整体缩放字号 |
| 图表 | MPAndroidChart 3.1.0（仅统计页） |
| 其他 | 无动画第三方库（没有 Lottie / Reanimated / Compose Animation 之外的东西） |

**重要限制（会直接影响你提方案）**：Compose 1.6.7 **没有** `SharedTransitionLayout`（1.7+）与 `Modifier.animateItem`（foundation 1.7+）。请只用 1.6.x 已有的 API（`animate*AsState`、`Animatable`、`AnimatedContent`、`AnimatedVisibility`、`Transition`、`animateContentSize`、`Modifier.graphicsLayer`、`pointerInput`）。

### 1.2 源码根目录与分层

源码根（下称 **`SRC`**）：

```
app/src_repo/app/src/main/java/com/kaoyan/wordhelper/
```

Kotlin 文件共 **137** 个，按目录分：

| 目录 | 文件数 | 内容 |
| --- | --- | --- |
| `data/` | 77 | Room 实体/DAO、仓库、网络（远程同步、重试、卡背拉取） |
| `ui/` | 27 | `ui/screen/` 12 个页面、`ui/component/` 3 个组件、`ui/navigation/`、`ui/theme/`、`ui/viewmodel/` |
| `util/` | 15 | 手势、音频、时间、格式化等 |
| `ml/` | 14 | 复习算法（SM-2 系）、`ml/ui/` 下的统计组件 |
| `sync/` | 2 | 后台同步（JobScheduler） |
| 根 | 2 | `KaoyanWordApp.kt`、`MainActivity.kt` |

**带动画的文件只有 6 个**（这是本次改造的全部战场；另有 1 个导航文件完全没有自定义转场）：

```
ui/screen/LearningScreen.kt            2634 行   ← 主战场（翻卡/滑动/卡背/转场）
ui/screen/BookManageScreen.kt           720 行
ui/screen/ProfileScreen.kt              833 行
ui/screen/SearchScreen.kt               690 行
ui/component/AnimatedStarToggle.kt       65 行
ml/ui/MemoryStabilityCard.kt            147 行
```

---

## 2. 用户可见的界面与交互流程

> 下面每一条都在源码里核对过。**没提到的交互不存在**，不要凭经验补。

### 2.1 底部导航（3 项）

`ui/navigation/AppNavigation.kt` → `bottomNavItems = [学习 / 词库 / 我的]`。

- `NavHost`（`AppNavigation.kt:122`）**没有自定义** `enterTransition` / `exitTransition`，走 navigation-compose 库的默认淡入淡出。
- 另有 5 个非底部路由：`sync_status`、`stats`、`ai_lab`、`book_build_guide`、`today_new_word_select/{bookId}`，以及一个 `search` 路由（已注册但当前 UI 里没有入口）。

### 2.2 学习页（`LearningScreen`）—— 核心

自上而下：**模式切换条 → 进度/今日信息 → 单词卡（正/背两面）→ 三个评分按钮**。

1. **模式切换条（`CollapsibleModeSwitcher`，`:1915-1967`）**：默认收起，只显示当前模式（认词/拼写）+ 一个箭头；点一下展开两个 Tab，**选完自动收起**。
2. **单词卡（`SwipeableWordCard` → `WordCard`）**
   - **轻触卡片 = 翻面**（正↔背）。翻面判定用自定义 `Modifier.cardFlipTap`（`:2250-2285`）：位移 ≤ 4dp 才算点击、**故意不显示水波纹**，且卡背滚动/点星星不会误触翻面。
   - **左滑（≥30% 卡宽）=「太简单」**：该词标记为太简单、30 天后复习；随后出现 3 秒可撤销的 Snackbar。
   - **右滑（≥30% 卡宽）=「加入生词本」**：加入生词本并直接切下一词。
   - 手势必须**起手在卡片中间 80% 区域**（边缘 10% 留给系统返回手势）。
   - 卡背内容可上下滚动；卡背底部是「重写区」`RewriteBar`（AI 重写入口）+ 反馈行，**只有背朝上时可交互**。
3. **三个评分按钮（`RecognitionActionPanel`）**：`不认识` / `模糊` / `认识`（在生词本模式下第三个变成「认识并移出」）。点按钮与滑动手势走**同一套换词动画**，但动画风格不同（见 `M02`–`M05`）。
4. 卡内还有：**发音按钮**（加载时按钮变 18dp 环形进度）、**星标**（加入/移出生词本，带缩放弹跳）、**「未复核」角标**（AI 生成未通过复核时出现，点击展开一行原因，无动画）。
5. 认词模式可以切到「AI 助手页」（`RecognitionAiAssistantPage`，`:1062`）：整页替换，**无转场**。
6. 一轮学完出现完成面板，底部有总进度条（`BottomLearningProgressBar`，`:1016-1059`）。

### 2.3 词库页（`BookManageScreen`）

顶部当前词书卡（含「卡背刷新」按钮）→ 词书列表（切换 / 删除 / 清空生词 / 拼写测试 / 提前复习 / 导出 / 详情）。导入词书时顶部出现不确定进度条；卡片操作走 Material3 `ModalBottomSheet`（`:152`）。

### 2.4 我的页（`ProfileScreen`）

`LazyColumn`（`:195-272`）依次：统计卡 → 数据管理卡（内含**远程同步状态入口**，显示一行摘要，点进 `SyncStatusScreen`）→ 设置卡 → 实验室入口 → 关于卡。

**同步状态页（`SyncStatusScreen`）本身没有任何动画**（纯静态列表）。

### 2.5 明确没有动效的页面

`SyncStatusScreen`、`SpellingScreen`、`StatsScreen`（除 `MemoryStabilityCard`）、`AILabScreen`、`StartupScreen`、`TodayNewWordsSelectScreen`、`BookBuildGuideScreen`、`RewriteBar` —— 均**未发现**任何动画 API。

---

## 3. 现有动效清单（逐条）

> 采集方式：对 137 个 `.kt` 全文 grep `animate|Animatable|tween|spring|Animated|Crossfade|graphicsLayer|scale|offset|rotation|Transition`，再逐处读源码确认参数。**命中即列，未命中不编。**
> 路径前缀统一省略为 `SRC/`（见 §1.2）。

### 3.1 学习页 · 换词转场

| 编号 | 位置 | 类型 | 关键参数 | 触发条件 |
| --- | --- | --- | --- | --- |
| `M01` | `ui/screen/LearningScreen.kt:548-555` | `AnimatedContent`（容器） | `transitionSpec = { wordCardTransition(lastRating) }`，`contentKey = { it.word.id }`，`SizeTransform(clip = false)` | 词 id 变化（换词）时 |
| `M02` | `:2541-2603`（GOOD 分支） | 组合转场 | 进：`slideInHorizontally(it/2, tween 220)` + `fadeIn(tween 180, delay 30, α0=0.42)` + `scaleIn(0.92, tween 220)`；出：`slideOutHorizontally(-it/3, tween 190)` + `fadeOut(tween 170, α→0.2)` + `scaleOut(1.03, tween 190)` | 点「认识」 |
| `M03` | 同上（AGAIN 分支） | 组合转场 | 进：`slideInHorizontally(-it, tween 320)` + `fadeIn(tween 220, α0=0.28)` + `scaleIn(0.90, tween 300)`；出：`slideOutHorizontally(it/2, tween 300)` + `fadeOut(220, α→0.16)` + `scaleOut(0.92, 280)` | 点「不认识」 |
| `M04` | 同上（HARD 分支） | 组合转场 | 进：`slideInVertically(it/4, tween 260)` + `fadeIn(210, α0=0.26)` + `scaleIn(0.95, 240)`；出：`slideOutVertically(-it/6, tween 230)` + `fadeOut(200, α→0.2)` + `scaleOut(0.97, 220)` | 点「模糊」 |
| `M05` | 同上（else 分支） | 组合转场 | `fadeIn(tween 180) togetherWith fadeOut(tween 150)` | `lastRating == null` 时（如滑动换词、首次加载） |

> 三档动画**都靠 `tween` 线性时长控制**，没有一个用 `spring`；整体换词时长 190–320ms 不等。

### 3.2 学习页 · 卡面翻转

| 编号 | 位置 | 类型 | 关键参数 | 触发条件 |
| --- | --- | --- | --- | --- |
| `M06` | `:1717-1721` | `animateFloatAsState` | `0f ↔ 180f`，`tween(300)`，label `wordCardRotation`；`showFront = derivedStateOf { rotation <= 90f }`；`cameraDistance = 12f × density`（`:1808`，只 `graphicsLayer` 重绘不重组） | 轻触卡片 |
| `M07` | `:1820` | 二值 alpha | 正面 `alpha = if (backVisible) 0f else 1f`（**无渐变，跨 90° 直接翻**） | 翻面跨过 90° |
| `M08` | `:2048` | 静态 `graphicsLayer` | 卡背 `rotationY = 180f`、`alpha = if (interactive) 1f else 0f`，`verticalScroll(enabled = interactive)`（`:2050`） | 背朝上时才可交互/可滚 |
| `M09` | `:1658`、`:1711-1712`、`:1856` | 预热挂载 | `LaunchedEffect(word.id) { backReady = false; delay(400); backReady = true }`；卡背靠 `if (backReady || isFlipped)` 常驻树里（**不是翻到 90° 才首次组合**） | 每次换词 |

> 结构要点：正反两面**同时存在**，跨 90° 只切 alpha 与背景 Brush（`:1756`），不重建子树——这是为提高翻面流畅度专门做的（根因探测见 `_recon/plan-flip-jank.md`，方案已落地）。

### 3.3 学习页 · 滑动手势

| 编号 | 位置 | 类型 | 关键参数 | 触发条件 |
| --- | --- | --- | --- | --- |
| `M10` | `:810-899` | 拖拽位移 | `detectHorizontalDragGestures`；`Modifier.offset { IntOffset(offsetX.roundToInt(), 0) }` —— **1:1 跟手，只有平移，无倾斜/缩放/旋转**；`coerceIn(-width, width)` | 水平拖动 |
| `M11` | `:876 / :888` | 回弹 | 未过阈值：`animate(current, 0f)` **未传 animationSpec** → 用默认 `spring()`（`DampingRatioNoBouncy`, `StiffnessMedium`=1500） | 松手未过阈值 / 取消 |
| `M12` | `:865 / :870` | 硬切归零 | 过阈值时 **`offsetX = 0f` 瞬间归零**，再回调切词（卡片先「闪回原位」，再由 `M02–M05` 播换词动画） | 松手已过阈值 |
| `M13` | `:854-857` | 触觉反馈 | 越过阈值瞬间 `performHapticFeedback(LongPress)`（只一次） | 位移跨过 30% |
| `M14` | `:902-980` | 整屏底色 | 左：`KnownGreen α 0.08 + 0.22×progress`；右：`FuzzyYellow α 0.10 + 0.24×progress`；无动作：`surfaceVariant α 0.10`。图标+文案固定在 CenterStart / CenterEnd | 随 `M10` 每帧线性变化 |
| `M15` | `:699-700`、`util/GestureHandler.kt:10-11` | 常量 | `SWIPE_TRIGGER_RATIO = 0.3f`、`SWIPE_ACTIVE_ZONE_RATIO = 0.8f` | — |

### 3.4 学习页 · 其他

| 编号 | 位置 | 类型 | 关键参数 | 触发条件 |
| --- | --- | --- | --- | --- |
| `M16` | `:1016-1059`（调用点 `:680`） | `animateFloatAsState` | 总进度 `tween(260)`，label `learningBottomProgress` | 进度变化 |
| `M17` | `:1915-1967` | `animateFloatAsState` + `Modifier.rotate` | 箭头 `0f ↔ 180f`，`tween(200)`，label `modeArrowRotation` | 展开/收起模式条 |
| `M18` | `:1915-1967` | `AnimatedVisibility` | 展开 `expandVertically(tween 200, from Top) + fadeIn(150)`；收起 `shrinkVertically(180, towards Top) + fadeOut(120)` | 同上 |

### 3.5 星标（组件）

| 编号 | 位置 | 类型 | 关键参数 | 触发条件 |
| --- | --- | --- | --- | --- |
| `M19` | `ui/component/AnimatedStarToggle.kt`（全文 65 行；使用点 `LearningScreen.kt:1787`、`SearchScreen.kt:454`、`:684`） | `Animatable(1f)` + `graphicsLayer` | 勾选：`animateTo(1.18f, tween 120)` → `animateTo(1f, spring(ζ=MediumBouncy, stiffness=MediumLow))`；取消：`animateTo(1f, tween 120)`；**首次组合有 `hasInteracted` 守卫直接 snapTo**，不会在列表滚动时抖 | 点星标 |

### 3.6 词库页

| 编号 | 位置 | 类型 | 关键参数 | 触发条件 |
| --- | --- | --- | --- | --- |
| `M20` | `ui/screen/BookManageScreen.kt:310-314` | `AnimatedVisibility` | `fadeIn(tween 260) + slideInVertically(it/4, tween 320)` | 首次组合（`:125-127` 的 `LaunchedEffect(Unit)` 置 true） |
| `M21` | `:371-376` | `AnimatedVisibility` | `fadeIn(180) + expandVertically(240)` | 正在导入 |
| `M22` | `:384-391` | 列表错峰 | `fadeIn(240, delay = min(i×30, 210)) + slideInVertically(it/3, 320, 同 delay)` | 列表项首次出现 |
| `M23` | `:523-527` | `animateFloatAsState` | 每张词书卡进度 `tween(550)`，label `bookProgress` | 进度变化 |
| `M24` | `:545` | `animateContentSize` | `tween(260)` | 卡片内容高度变化 |
| `M25` | `:152` | 库内置 | Material3 `ModalBottomSheet` 自带进出场 | 打开操作表 |

### 3.7 我的页

| 编号 | 位置 | 类型 | 关键参数 | 触发条件 |
| --- | --- | --- | --- | --- |
| `M26` | `ProfileScreen.kt:132-143` | 分段揭示 | `statsVisible=true; delay(45); dataVisible; delay(45); settings; delay(45); ai; delay(45); about` —— 5 段，45ms 步进 | 首次组合 |
| `M27` | `:195-272` | 5× `AnimatedVisibility` | `fadeIn(220) + slideInVertically(it/4, 300)`，**未写 exitTransition**（用默认退出） | 各段 visible |

### 3.8 查词页

| 编号 | 位置 | 类型 | 关键参数 | 触发条件 |
| --- | --- | --- | --- | --- |
| `M28` | `SearchScreen.kt:170-174` | `AnimatedVisibility` | `fadeIn(260) + slideInVertically(it/4, 320)` | 首次组合 |
| `M29` | `:209-213` | `AnimatedVisibility` | `fadeIn(220) + expandVertically(300)` | 长句解析面板出现 |
| `M30` | `:234-240` | 列表错峰 | `fadeIn(240, delay min(i×30,210)) + slideInVertically(it/3, 320, 同 delay)` | 结果项出现 |
| `M31` | `:267`、`:423` | `animateContentSize` | `tween(260)` / `tween(220)` | 内容高度变化 |

### 3.9 统计组件

| 编号 | 位置 | 类型 | 关键参数 | 触发条件 |
| --- | --- | --- | --- | --- |
| `M32` | `ml/ui/MemoryStabilityCard.kt:54-58`（使用点 `StatsScreen.kt:160`） | `animateFloatAsState` | 稳定度指数 `tween(600)`，同时驱动进度条 / 百分比数字 / 颜色 | 数据变化 |

### 3.10 导航

| 编号 | 位置 | 类型 | 说明 |
| --- | --- | --- | --- |
| `M33` | `ui/navigation/AppNavigation.kt:122` | **无** | `NavHost` 未自定义转场，用库默认淡入淡出（时长以库默认值为准，需实测确认） |

### 3.11 「未发现」的（重要，避免你建议重复造轮子）

- 全项目**没有** `rememberInfiniteTransition` / `infiniteRepeatable`（无循环/呼吸/骨架屏动画）。
- 没有 `Crossfade`、没有 `updateTransition`、没有 `keyframes`、没有自定义 `Easing` 曲线（除 `spring` 默认）。
- 没有 `MotionLayout` / `AnimatedImageVector` / `PathParser` 动画。
- 没有共享元素 / 场景转场。
- **没有一处读取 `LocalMotionDurationScale` 或无障碍「减少动态效果」设置**（grep `AccessibilityManager|LocalMotionDurationScale|reduceMotion|MotionDurationScale` 在 137 个文件里 0 命中）。

> 关于无障碍的准确结论：Compose 的 `tween` / `spring` 会跟随系统「动画时长缩放」自动归零，但**硬编码的延迟不会**——`M09` 的 `delay(400)` 与 `M26` 的 `delay(45)×4` 在系统关动画时仍会等待。这是真实存在的可访问性缺口。

---

## 4. 值得调的 5 个候选动效

> 每条：**现状 → 问题 → 落点 → 可选方向（不替你决定）→ 风险**。
> 「问题」里凡属推断的都标了「待实测」。

### 4.1 C1 · 换词转场（`M01`–`M05`）

- **现状**：一次换词 = 滑入 + 淡入 + 缩放 三个动画叠在一起，`tween` 线性，三档时长 190/220/260/320ms 不等；容器带 `SizeTransform(clip = false)`，换词期间新旧卡片同时存在。
- **问题**：
  1. 全是 `tween`，没有收尾的减速曲线差异；「认识」220ms 与「不认识」320ms 的差别主要靠时长，缺少「重量」的统一语言。
  2. `SizeTransform(clip = false)` 关闭裁剪后，换词期间新旧两张卡**同时参与测量与绘制**，成本翻倍（待实测是否在低端机掉帧）。
  3. 三档动画的位移方向（右/左/上）与手势方向不完全对应，用户第二次操作时容易「猜不到这张卡会从哪来」。
- **落点**：`ui/screen/LearningScreen.kt:2541-2603`（`wordCardTransition`）。
- **可选方向**：
  - (a) **时长归一**：三档收敛到同一时长（如 240ms），用**位移距离与缩放幅度**表达差异，读起来更一致。
  - (b) **弹簧收尾**：位移用 `spring(stiffnessMediumLow, ζ≈0.8)` 替代 `tween`，强调「卡片有质量」。
  - (c) **只改进入、不动退出**：保留现有退场节奏，仅让新卡带一点过冲，改动面最小。
- **风险**：`M09` 的 400ms 延迟挂卡背是为「最长 320ms 换词」算的；若把换词时长改到 >400ms，卡背会先挂上来，翻面会漏底。

### 4.2 C2 · 卡背翻转（`M06`–`M08`）

- **现状**：`rotationY` 0↔180，`tween(300)`，`cameraDistance = 12 × density`。
  **注意：正反两面是常驻的**——`LearningScreen.kt:1856` 的 `if (backReady || isFlipped)` 在换词 400ms 后就把卡背挂进树里，`:1820` / `:2048` 各自用 `graphicsLayer { alpha }` 做显隐门控，跨过 90° 时只切 alpha 与背景 Brush（`:1756`），**不重建子树、不触发首次测量**。
  （这点很关键：`_recon/plan-flip-jank.md` 里「90° 那一帧多付 12–25ms」是**改造前**的结论，当前代码已经落地了它的 A′ 方案，那一处不再是瓶颈。）
- **问题**：
  1. 等速旋转，180° 落定时没有减速/停顿，手感偏机械。
  2. 翻转过程中**没有阴影/景深变化**，立体感全靠透视；`cameraDistance = 12 × density` 偏小（透视偏「广角」），姿态有点贴脸。
  3. 翻到一半再点，`animateFloatAsState` 从当前角度反向重算、**没有速度继承**，连续快点会显得迟疑。
  4. 90° 处的 alpha 是硬切，但那一刻两面正好都侧对屏幕（边缘朝外），肉眼基本无感；只有把系统动画缩放到很慢才可能察觉。**不是优先项。**
- **落点**：`ui/screen/LearningScreen.kt:1717-1721`（rotation + `showFront`）、`:1756`（背景 Brush 切换）、`:1820`（正面 alpha）、`:2048`（卡背 alpha）。
- **可选方向**：
  - (a) **弹簧翻面**：`spring(dampingRatio = 0.82f, stiffness = 200f)`，落定有约 2° 过冲再回正（仓库里已有这个方向的可交互原型，见 §4.6）。
  - (b) **缓入缓出**：仅把 `tween(300)` 换成 `tween(300, easing = FastOutSlowInEasing)`，最保守、零风险。
  - (c) **阴影跟随**：翻转时按 `|sin(rotation)|` 抬一点阴影/缩放，强化「翻过去」。
- **风险**：弹簧的过冲发生在 **180° 那一端**（约 182° 再回落），过程中**只单调穿过一次 90°**，所以 `showFront` 不会来回翻转；但要确认「转过头 2°」时卡片背面边缘不会穿帮（尤其如果同时加了缩放/阴影）。另：`showFront` 现在还驱动背景 Brush（`:1756`），如果动到它，记得保持「只在翻转时切一次」。

### 4.3 C3 · 滑动手势跟手（`M10`–`M14`）

- **现状**：卡片 1:1 平移，无倾斜/缩放；整屏底色随进度线性改 alpha；越过 30% 有一次性震动；松手过阈值时**先瞬间归零**再播换词动画；未过阈值用默认 `spring` 弹回。
- **问题**：
  1. 卡片只平移，没有「被拎起来」的反馈（无倾斜、无轻微放大、无阴影加深），手势的物理感弱。
  2. **`M12` 的硬切归零是最突兀的一处**：松手瞬间卡片跳回原位，紧接着换词动画又从侧面滑入，视觉上像「闪了一下」。
  3. `M11` 的默认 spring（stiffness 1500）回弹偏快偏硬，与卡片这种大面积的元素不匹配。
  4. 阈值反馈只有震动，没有视觉上的「已锁定」状态。
- **落点**：`ui/screen/LearningScreen.kt:810-899`（拖拽与松手）、`:902-980`（背景指示）、`:699-700`（阈值常量）。
- **可选方向**：
  - (a) **加轻微跟随姿态**：`scale = 1 - 0.04 × progress`、`rotationZ = ±1.5° × progress`，跟手反馈更明确（注意：旋转会让 `graphicsLayer` 每帧重建矩阵，需实测帧率）。
  - (b) **去掉闪回**：命中时不做 `offsetX = 0f`，改为让卡片沿手势方向**继续飞出**（与换词动画首帧衔接），或用 80–120ms 的 `tween` 归零替代硬切。
  - (c) **只改回弹**：`M11` 换成 `spring(dampingRatio = 0.75, stiffness = MediumLow)`，一处改动即可显著改善手感。
- **风险**：`M10` 的拖拽是 1:1 映射，任何「跟随缩放」都会让松手位置与阈值判断不一致；`cardFlipTap` 用 4dp 位移判定点击，加旋转/缩放不会影响它，但加**动画化的 offset 归零**会让「点击」和「回弹」在同一帧竞争，需要保证两者互斥。

### 4.4 C4 · 卡背重写区：动效缺失（`RewriteBar`）

- **现状**：卡背底部的重写条与「重写结果替换原文」**完全没有动画**——文字直接替换，条子直接出现。
- **问题**：这是全 App 里**文字变化最剧烈**的一处（三行推导被整段重写），却是唯一没有过渡的；用户容易「没看出哪里变了」或者「感觉闪了一下」。
- **落点**：`ui/screen/RewriteBar.kt`（自身无动画）+ 卡背容器 `LearningScreen.kt:2030-2060`。
- **可选方向**：
  - (a) **逐行换文**：旧文淡出 120ms（上移 6px）→ 40ms 间隔 → 新文淡入 300ms（从下 10px 归位），三行错峰 140ms。
  - (b) **整块交叉淡入**：最简单，一次性 `Crossfade` 式的 alpha 过渡，不做位移。
  - (c) **重写条入场**：重写区随卡背滚动进入视口时渐显并上移 12px（460ms，每张卡只播一次），且**始终占位**，出现时不顶动正文。
- **风险**：卡背是 `verticalScroll`，逐行换文时如果内容高度变化，滚动位置会跳；必须保证换文前后行高一致（或用固定高度容器）。仓库里 A/C 两个原型已专门针对这两点做过验证（见 §4.6）。

### 4.5 C5 · 页面转场与列表入场（`M20`–`M33`）

- **现状**：`NavHost` 无自定义转场；词库 / 搜索结果 / 我的页各自用「30ms×index（上限 210ms）+ 淡入 + 1/4 高度上滑 300–320ms」；我的页 5 段靠硬编码 45ms 步进；所有 `AnimatedVisibility` 基本都是单向。
- **问题**：
  1. 页面切换与卡片内动画**不是同一套语言**（页面靠库默认淡入淡出，卡片靠滑入+缩放）。
  2. 列表错峰的 `delay` 上限 210ms + 每项 320ms，长列表末尾十几项会几乎同时运动，视觉上像「一起涌上来」。
  3. `M27` 未写退出 spec，退出用的是 Compose 默认（`fadeOut`），与进入的 320ms 上滑不对称。
  4. `M26` 的 `delay(45)×4` 硬编码，系统关动画时不生效（见 §3.11）。
- **落点**：`AppNavigation.kt:122`；`BookManageScreen.kt:384-391`；`ProfileScreen.kt:195-272`；`SearchScreen.kt:234-240`。
- **可选方向**：
  - (a) **统一一套 `MotionTokens`**：把时长/曲线提取成常量对象（`enterFast = 220ms`、`enterSlow = 320ms`、`easeOut`…），四处共用。
  - (b) **只收拾最刺眼的一处**：让 `M27` 补上对称的 exit spec。
  - (c) **错峰改成按可见区域**：只对首屏前 6 项做错峰，其余直接显示（避免长列表末尾一起涌）。
- **风险**：改 `NavHost` 转场会影响所有页面的返回手势与状态恢复；如果要做，建议**只加 `enterTransition`/`exitTransition` 而不要动 `popEnter/popExit`** 的默认行为。

### 4.6 已有的参考与已有结论（重要：请先看，再提新方案）

#### (1) 三个动效方向的原型（HTML，已做，未落地）

仓库里**已经存在**三个动效方向的离线 HTML 原型（2026-11-08 制作，位于 `docs/motion-ref/`，可直接双击打开，自带「重播 / 慢放 ×2.5」）：

| 原型 | 主题 | 关键参数（源码顶部 `MOTION` 常量） |
| --- | --- | --- |
| `docs/motion-ref/A-approach-swap.html` | 换文（三行推导逐行替换） | 错峰 140ms；旧文 out 120ms / −6px；间隔 40ms；新文 in 300ms / +10px；入场模糊 1.5px；`cubic-bezier(.23,1,.32,1)` |
| `docs/motion-ref/B-flip-spring.html` | 翻面（弹簧） | `0→180°`；阻尼比 **0.82**（过冲 ≈1.1% ≈2°）；stiffness **200**；mass 1；**perspective 1400px**；落定阈值 0.15° |
| `docs/motion-ref/C-bar-reveal.html` | 重写条入场 | 460ms；起点 +12px；可见 18% 触发；`cubic-bezier(.23,1,.32,1)` |

原型页面对「怎么判断好坏」写得很清楚（原文）：

> 不看「有没有动画」，看三件事：文字有没有重影（A 的旧文/新文是否抢同一块像素）、落定有没有回弹感（B 的 2° 过冲是不是刚好）、出现时正文有没有被顶一下（C 是否只动 transform / opacity）。

**但这些方向目前一个都没落地**：翻面仍是 `tween(300)`（`M06`），重写条仍无动画，换文仍无过渡。所以：**这些原型是「期望手感」的候选答案，不是现状描述**。你可以基于它们给 Compose 落地建议，也可以提出更好的；但不要把它们当成已实现的功能去描述。

#### (2) 已经做过的翻面卡顿根因探测（结论：已修，别重复推导）

`_recon/plan-flip-jank.md` 是一份只读探测报告，当时的结论是：**卡顿不在时长、也不在 `cameraDistance`，而在「rotation 跨过 90° 那一帧，卡背 14 段 Text / ~40 个 composable 首次组合+测量，多付 12–25ms」**；它给出的方案 A′ 是「两面常驻 + 换词后 400ms 静止帧预组合 + alpha 门控」，约 26 行。

**这份报告的结论已经体现在当前代码里**（`LearningScreen.kt:1856` 的 `backReady || isFlipped`、`:1820`/`:2048` 的 alpha 门控、`:1711` 的 400ms 预热）。所以：

- ✅ 不要再说「翻面会在 90° 掉帧」——那是历史状态。
- ✅ 报告里的行号是**旧快照**（现在整段大约后移了 28 行），引用前请重新定位。
- ⚠️ 报告里还有两条**至今仍然成立**的观察：`wordCardTransition` 里 `SizeTransform(clip = false)` 会让换词期间新旧两卡同时测量（见 §4.1 C1）；`CardBack.kt` 的 `renderableBlocks` 每次读取都会 `filter {}` 新分配一个 List（`LearningScreen.kt:2053` 调用）。

---

## 5. 给 GPT 的输入包建议

### 5.1 明确的：**不要给全仓库**

理由是具体的，不是客套：

- 137 个 `.kt` 里 **77 个在 `data/`**，全是 Room/网络/同步/重试逻辑，与动效无关，只会稀释注意力。
- 这些文件里混着**同步协议、鉴权与口令路径**（见 §5.3），外发即泄密。
- 动效只集中在 **6 个文件**，按片段给足够。

### 5.2 建议的输入包（4 件套）

| # | 内容 | 说明 |
| --- | --- | --- |
| (a) | **本文档** | 结构、现状、动效清单、约束都在里面 |
| (b) | **动效相关源码片段**（5–10 个文件，见下表） | 优先给**片段**而不是整文件；`LearningScreen.kt` 有 2634 行，不要整份上传 |
| (c) | **3–5 段录屏 / 截图** | 建议：①点「不认识」换词全过程（慢放）②卡片翻面一次 ③左右各滑一次（含松手瞬间）④卡背重写一次。**帧率 60fps 录**，手指可见 |
| (d) | **约束声明**（可直接复制） | 「Material 3；不引入任何新依赖；目标 60fps；必须尊重系统『减少动态效果』；不重写整页，只给 diff」 |

#### 建议上传的文件清单（含建议给的行段）

| 文件 | 行数 | 给什么 | 为什么 |
| --- | --- | --- | --- |
| `ui/screen/LearningScreen.kt` | 2634 | **只给 5 段**：`:540-570`、`:800-990`、`:1700-1830`、`:1910-1975`、`:2230-2300`、`:2530-2610` | 换词容器 / 手势 / 翻转 / 模式条 / tap 判定 / 转场定义 |
| `ui/component/AnimatedStarToggle.kt` | 65 | 全文 | 唯一独立的动画组件，可以直接当风格样板 |
| `ui/screen/BookManageScreen.kt` | 720 | `:120-135`、`:300-400`、`:515-550` | 入场 + 错峰 + 进度 |
| `ui/screen/ProfileScreen.kt` | 833 | `:125-150`、`:190-275` | 分段揭示 + AnimatedVisibility |
| `ui/screen/SearchScreen.kt` | 690 | `:165-245` | 入场 + 错峰（与词库同款） |
| `ui/theme/Theme.kt` + `Color.kt` + `Type.kt` | 78 + ? | 全文 | 让建议贴合现有色板与字号 |
| `util/GestureHandler.kt` | 41 | 全文 | 阈值/活跃区的唯一真相来源 |
| `docs/motion-ref/index.html` + 3 个 HTML | — | 全文或截图 | 已有手感参考（§4.6） |

### 5.3 可给 / 不可给 清单

| ✅ 可给 | ❌ 绝对不可给 |
| --- | --- |
| `ui/screen/` 下与动效相关的片段 | `tools/cardback-editor/.cardback_token`（电脑端生成的鉴权口令） |
| `ui/component/AnimatedStarToggle.kt` | `data/` 运行时数据：`feedback.jsonl`、`sync.json`、`kaoyan_words.db` |
| `ui/theme/*`（颜色/字体/主题） | 任何真实用户的词表、学习记录、反馈原文 |
| `util/GestureHandler.kt` | 服务器地址、Tunnel token、CI/签名配置 |
| `docs/motion-ref/*`（原型 HTML） | `_toolchain/signing/wordhelper.jks`（**签名文件，含明文口令，绝不外发**） |
| 屏幕录制 / 截图（**需脱敏**：见下） | `app/src_repo/gradle/` 下的依赖锁定与仓库凭据 |

**脱敏规则（给源码子集时同样适用）**：

1. 截图/录屏里的单词内容若是自建词库，**用测试词替换**（如 `abandon` / `benefit` / `chronic` 这类通用词）。
2. 删掉所有 URL、IP、端口号、token、`Bearer` 字样、手机号、设备型号。
3. 源码片段里若出现 `BuildConfig.XXX`、`token`、`password`、`secret`、`http(s)://`，一律替换成 `***`。
4. 不要在文档或截图里出现「用户的 👎 原话」这类反馈原文（属于真实用户数据）。

---

## 6. 要求 GPT 的输出格式

### 6.1 一次只改一个

- 每次回复**只处理一个动效编号**（`C1`–`C5` 或 `M01`–`M33`）。
- 开头一行写：`目标：C2 卡背翻转；落点：ui/screen/LearningScreen.kt:1715-1719`。
- 如果觉得这个动效不该改，直接说「不改，理由是…」，比硬给建议有用。

### 6.2 diff 级修改建议（模板）

```markdown
## 改动 C2 · 卡背翻转
文件：ui/screen/LearningScreen.kt
函数：WordCard 内的 rotation（约 :1715-1719）

- val rotation by animateFloatAsState(
-     targetValue = if (isFlipped) 180f else 0f,
-     animationSpec = tween(durationMillis = 300),
+ val rotation by animateFloatAsState(
+     targetValue = if (isFlipped) 180f else 0f,
+     animationSpec = spring(
+         dampingRatio = 0.82f,
+         stiffness = Spring.StiffnessMediumLow
+     ),
      label = "wordCardRotation"
  )

配套改动（必须一起给）：
- :1820 的 `if (backVisible) 0f else 1f` 需要加 120ms 交叉，否则 90° 处会闪。
- 需验证 `derivedStateOf { rotation <= 90f }` 在过冲时不来回穿越。
```

要求：
- 用 `-` / `+` 标注增删，**只贴改动附近的上下文**（±5 行），不要贴整个函数。
- 每个数值改动都要写**为什么是这个值**（一句），不要只列数字。
- 若需要新增常量，明确放在哪个文件、叫什么名。

### 6.3 每条建议必须附验收标准

| 维度 | 怎么写才算合格 |
| --- | --- |
| **时长** | 明确到 ms 或弹簧参数，并给出可接受的区间（例：「220–260ms，超过 300ms 会觉得拖」） |
| **帧率** | 说明如何验证（例：「开发者选项 → GPU 渲染模式 → 该动效期间无红柱；或 `Choreographer` 日志」），并指出**预期风险点**（例：「`graphicsLayer` 每帧重建矩阵」） |
| **回归点** | 列出改完后**必须重测**的既有交互（例：「翻到一半再点要能反向」「卡背滚动后仍能点回正面」「点星星不能连带翻面」） |
| **可访问性** | 系统「动画时长缩放 = 0」时该动效应表现为**瞬时到位**，而不是卡在半路 |
| **不改坏** | 明确说这次改动**没有**动到哪些文件/行为 |

### 6.4 禁止事项

- ❌ 不要重写整个页面/整个文件。
- ❌ 不要换框架、不要引入新依赖（含 Lottie、Reanimated、第三方动画库）。
- ❌ 不要改数据层、同步逻辑、反馈闭环、导航结构。
- ❌ 不要建议使用 Compose 1.6.7 不存在的 API（`SharedTransitionLayout`、`Modifier.animateItem`、`rememberSharedTransitionScope`）。
- ❌ 不要假设能跑构建或看真机——你只能给代码，验证由人来做。

---

## 7. 需要用户拍板的 3 件事

1. **给不给源码子集？**
   - 给：GPT 能给出直接可用的 diff（推荐，但只给 §5.2 表里的片段）。
   - 不给：只凭本文档 + 录屏，只能得到「方向性建议」，无法给出可编译的具体参数。
2. **要哪种手感方向？**（每条都是取舍，没有标准答案）
   - **克制派**：只修最刺眼的两处（`M12` 硬切归零、`M06` 线性翻面），时长微调，不新增动画。
   - **连贯派**：统一一套 Motion Token（时长/曲线），把 C1/C2/C3/C5 的节奏对齐。
   - **表现派**：落地 §4.6 的三个原型（逐行换文 + 弹簧翻面 + 重写条入场），手感最强，改动也最大。
3. **目标设备与最低帧率？**
   - 需要明确一台**基准机**（型号 + Android 版本），以及「不达标」的定义（例：该动效期间帧率不低于 55fps / 无 3 帧以上的卡顿）。
   - 这会直接决定能不能加 `rotationZ`、能不能对整屏做逐帧透明度、能不能在长列表上做错峰。

---

## 附录 A · 动效相关文件速查

```
SRC = app/src_repo/app/src/main/java/com/kaoyan/wordhelper

SRC/ui/screen/LearningScreen.kt        2634 行  M01-M18
SRC/ui/screen/BookManageScreen.kt       720 行  M20-M25
SRC/ui/screen/ProfileScreen.kt          833 行  M26-M27
SRC/ui/screen/SearchScreen.kt           690 行  M28-M31
SRC/ui/component/AnimatedStarToggle.kt   65 行  M19
SRC/ml/ui/MemoryStabilityCard.kt        147 行  M32
SRC/ui/navigation/AppNavigation.kt      —      M33（无自定义转场）
SRC/util/GestureHandler.kt               41 行  阈值常量（M15）
docs/motion-ref/A-approach-swap.html    —      换文原型
docs/motion-ref/B-flip-spring.html      —      弹簧翻面原型
docs/motion-ref/C-bar-reveal.html       —      重写条入场原型
```

## 附录 B · 关键常量

| 常量 | 值 | 位置 | 含义 |
| --- | --- | --- | --- |
| `SWIPE_TRIGGER_RATIO` | `0.3f` | `LearningScreen.kt:699` | 滑动触发阈值（卡宽占比） |
| `SWIPE_ACTIVE_ZONE_RATIO` | `0.8f` | `LearningScreen.kt:700` | 有效起手区域（中间 80%） |
| `RECOGNITION_SECTION_SPACING` | `10.dp` | `LearningScreen.kt:698` | 学习页区块间距 |
| `DEFAULT_TRIGGER_RATIO` / `DEFAULT_ACTIVE_ZONE_RATIO` | `0.3f` / `0.8f` | `util/GestureHandler.kt:10-11` | 上面两个的同源默认值 |
| 卡背延迟挂载 | `400ms` | `LearningScreen.kt:1711` | 等换词动画（最长 320ms）结束 |
| 我的页分段步进 | `45ms × 4` | `ProfileScreen.kt:132-143` | 5 段依次揭示 |
| 列表错峰上限 | `min(index × 30, 210)ms` | `BookManageScreen.kt:384`、`SearchScreen.kt:234` | 每项延迟 |
| 翻面时长 | `300ms` | `LearningScreen.kt:1717` | `tween` |
| 透视 | `12f × density` | `LearningScreen.kt:1808` | `cameraDistance` |
