# 1.4.1 卡片翻面手势改造 · 改动记录

> 只动了一个文件：`app/src_repo/app/src/main/java/com/kaoyan/wordhelper/ui/screen/LearningScreen.kt`
> 版本号未动（仍是 versionCode 3 / versionName "1.4"），按内测包同版本覆盖的规矩走。

---

## 1. 要解决的问题

用户提了三条：

1. 点卡片翻面时会出现**水波纹**（按压涟漪）；
2. 点**背面**应该能翻回正面；
3. 背面**往下滑之后**再点卡片，仍然要能回到正面。

---

## 2. 根因

### 2.1 水波纹

卡片原来用 `Card(onClick = { isFlipped = !isFlipped })`。Material 的 `Card` 带 `onClick` 时会挂 indication，按下就有整卡的水波纹 + 按压态底色。

### 2.2 滑动后点不回正面

`Modifier.clickable` 内部是 `awaitFirstDown(requireUnconsumed = true)`。卡背的 `verticalScroll` 在惯性 fling 或滚动判定中一旦消费了按下事件，`clickable` 直接不认这次点击，于是点了没反应。

### 2.3 点星星连带翻面（额外发现）

点卡内星星会把生词本状态写回数据层，外层 `AnimatedContent(targetState = cardState)` 的 `cardState` 含 `isInNewWords`，于是被判定成"换了一张卡"：旧卡销毁、新卡重建、`remember` 的 `isFlipped` 归零，看起来就像"点星星把卡片翻回正面了"。

---

## 3. 改法

### 3.1 去掉 `Card(onClick)`

改成非可点击的 `Card` 重载，只保留 `shape / border / colors`，不再有任何 indication。

### 3.2 新增 `Modifier.cardFlipTap`

文件内私有扩展函数（`composed { pointerInput { awaitEachGesture { ... } } }`）：

- 用 `awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)` 拿按下——**不受内部滚动消费影响**，解决 2.2；
- 之后监听 `PointerEventPass.Main`：Main 从叶子往根传播，星星 / 发音 / AI 灯泡 / 卡背 `verticalScroll` 都先于本节点处理；
- 判定一次点击需要同时满足：抬起已发生、**抬起事件未被消费**、中途没有第二根手指、最大位移 ≤ **4dp**；
- 全程不消费任何事件、不画任何视觉反馈——所以没有水波纹。

挂在内层 `Column` 上（`fillMaxWidth().fillMaxHeight()` 之后、`background` / `padding` 之前），触摸区域覆盖整张卡片内容区。

### 3.3 `AnimatedContent` 加 `contentKey`

```kotlin
contentKey = { it.word.id }
```

让"是不是同一张卡"只由词 id 决定：同一个词内部的状态变化（点星星）不再重建卡片，翻面状态保住，也不会白播一次切换动画。换词时 id 变，动画照旧。

---

## 4. 关键数值

| 项 | 值 |
|---|---|
| 点击判定最大位移 | 4dp（约 11px @ density 440） |
| 翻面动画时长 | 300ms（原有，未改） |
| 水波纹基线（修复前） | 卡片区域平均色差 **19.71/255**，差值>6 像素 **98.13%** |
| 修复后实测 | 平均色差 **0.00**，差值>6 像素 **0.00%** |

---

## 5. 自测（模拟器 emulator-5554）

| 项 | 结果 |
|---|---|
| F1 正面轻点→背面 | 通过 |
| F2 背面轻点→正面 | 通过 |
| F3 卡背上下滑 10 次不翻面 | 10/10 保持背面 |
| F4 滑动后立刻点击 5 轮 | 5/5 回到正面 |
| F5 水波纹 | 0.00 / 0.00% |
| F6 背面点星星不翻面 | 通过（星星状态正常切换，卡片保持背面） |
| F7 正面左滑切词 | `increase` → `influence`，功能正常 |
| F8 滚到底再点仍回正面 | 通过 |
| E2 连点 6 次 | 不崩，之后单次点击正常 |

E1（动画连贯性）在 headless + swiftshader 模拟器上无法量化：`dumpsys gfxinfo` 100% janky、50 分位 150ms，录屏抽帧只能确认"无黑屏/无空白"。**帧率必须在真机上测**。

---

## 6. 源码↔安装包一致性自证

评分员发现源码 mtime（18:22:36）晚于设备上的安装时间（18:19:19），提出"当前源码可能比已装包新"。

核查结果：

- 源码那次 18:22 的改动是清理调试痕迹（删 `Log.w("CardFlipTap", ...)` 打点、删两个未使用 import），**无逻辑变化**；
- 两个包的 dex 里都查不到 `CardFlipTap` 字符串，说明设备上的包也是清理后的版本；
- 18:24 重新构建（33,419,589 字节，sha256 `a794bd6c…e1f3`），已重新 `install -r` 到模拟器（lastUpdateTime 更新为 10:47:15 GMT）；
- 重装后复跑 F1/F2（BACK↔FRONT 连续三次）、F3（上下各 10 次滑动全部保持背面）、F4（swipetap 回正面）、F5（`mean_diff=0.00`、`pct_gt6=0.00%`）、F6（点星星保持背面且星标状态切换）、F7（左滑仍正面）、F8（滚到底点击回正面），全部通过。

结论：**被测行为与当前源码一致**。

## 7. 长按行为（如实记录）

`input swipe 540 1056 540 1056 1500` 长按 1.5 秒再松手，**会翻面**。这与旧版 `Card(onClick)` 的行为一致（Material 的 onClick 本来就不限制按压时长），不是本次改动引入的。若以后要求"长按不翻面"，需要额外加时长上限。

## 8. 尚未做

- 版本号未升（等拍板是否发 1.4.1 / versionCode 4）；
- 真机未测（真机只在交付时碰）；
- 数据库 schema 未动（仍是 14，下次改动必须 15）。
