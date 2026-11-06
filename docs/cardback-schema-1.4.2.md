# 卡背清单 schema 契约（1.4.2）

> 这份文件是**唯一事实源**：手机端渲染、电脑端编辑器、本地服务、测试用例都按它实现。
> 改之前先改这里，改完同步升 `schemaVersion`。

---

## 0 一句话

卡背不再写死在 `WordCard` 里，改成**按一份有序的块列表渲染**。电脑上改这份 JSON，手机上点刷新拉下来，不重装、不动进度。

---

## 1 JSON 结构

```json
{
  "schemaVersion": 1,
  "book": "六级记忆卡",
  "revision": 1,
  "updatedAt": "2026-11-06T20:30:00+08:00",
  "spacing": 12,
  "motion": {
    "titleDurationMs": 820,
    "staggerGapMs": 90,
    "entryDurationMs": 220,
    "overshootPercent": 72,
    "overshootPx": -1.0,
    "overshootScale": 1.0015
  },
  "blocks": [
    { "id": "word",   "type": "TITLE",    "visible": true, "title": null,   "style": null },
    { "id": "ipa",    "type": "PHONETIC", "visible": true, "title": null,   "style": null },
    { "id": "div1",   "type": "DIVIDER",  "visible": true, "title": null,   "style": null },
    { "id": "mean",   "type": "MEANING",  "visible": true, "title": "释义", "style": null }
  ]
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `schemaVersion` | int | 是 | 目前恒为 `1`。手机只认 `<= 1`，更大就整份拒收（退回上一版） |
| `book` | string | 否 | 词书名，仅用于编辑器显示，手机不校验 |
| `revision` | int | 是 | **单调递增**。手机用它判断「是不是比手上的新」 |
| `updatedAt` | string | 否 | ISO8601，仅展示 |
| `spacing` | int | 否 | 块间距 dp，默认 `12`，范围 `4..32` |
| `motion` | object | 否 | 动效参数，缺省用内置值，见 §5 |
| `blocks` | array | 是 | **有序**，渲染顺序 = 数组顺序。空数组视为非法（退回上一版） |

### block 对象

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 是 | 唯一。`DIVIDER`/重复类型必须靠它区分 |
| `type` | string | 是 | 见 §2，不认识就**跳过并提示**，不报错 |
| `visible` | bool | 否 | 默认 `true`。`false` = 不渲染（用户自己关的） |
| `title` | string | 否 | `null` = 用该类型的默认标题；空串 `""` = **不显示标题**（只有内容） |
| `style` | object | 否 | 见 §3 |

---

## 2 块类型（全部预置在代码里）

| type | 内容来源 | 默认标题 | 无内容时 |
|---|---|---|---|
| `TITLE` | `word.word` | —（不显示标题） | 永远有 |
| `PHONETIC` | `word.phonetic` | — | 自动跳过 |
| `DIVIDER` | 无线条内容 | — | 永远显示 |
| `MEANING` | `word.meaning`，按词性分行 | `释义` | 自动跳过 |
| `MEMORY` | `word.approach`，一行一条 | `记忆思路` | 自动跳过 |
| `COLLOCATIONS` | `word.collocations`，一行一条 | `常用搭配` | 自动跳过 |
| `EXAMPLE` | `word.example` | `例句` | 自动跳过 |
| `PHRASES` | `word.phrases` | `短语` | 自动跳过 |
| `SYNONYMS` | `word.synonyms` | `近义词` | 自动跳过 |
| `REL_WORDS` | `word.relWords` | `同根词` | 自动跳过 |
| `EF_STATS` | `progress`（EF 系数 / 偏移 / 间隔 / 次数） | `""`（无标题） | 无进度时跳过 |
| `REWRITE_BUTTON` | 1.4.3 预留 | `重写思路` | 1.4.2 **不认识 → 跳过** |

**红线**：`type` 不能凭空产生新行为。任何新按钮的功能必须先进代码，配置只能决定「出不出 / 放哪 / 文案 / 颜色」。

**1.4.2 的默认块表（= 现在卡背的样子，一像素都不许变）**

```
TITLE → PHONETIC → DIVIDER → MEANING → MEMORY → COLLOCATIONS
→ EXAMPLE → PHRASES → SYNONYMS → REL_WORDS → EF_STATS
```

共 **11 块、只有 1 条分隔线**。`EF_STATS` 面板**自带**上方那条分隔线（1.4.1 里它本来就是跟面板一起出现、一起消失的），所以**不要在它前面再放一个 `DIVIDER`**：那会在无进度时剩一条孤零零的线，有进度时又会画出两条。

---

## 3 style

```json
{ "color": "primary", "size": "M" }
```

| 字段 | 取值 | 说明 |
|---|---|---|
| `color` | `default` `primary` `secondary` `accent` `muted` `warning` | **只下发语义名，绝不下发 hex** |
| `size` | `S` `M` `L` | 内容文字字号档 |

**为什么不下发颜色值**：App 有暗色模式。写死 `#FF3B30` 在暗色下会糊成一片。语义名由 App 映射到 `MaterialTheme.colorScheme`：

| 语义名 | 映射 | 备注 |
|---|---|---|
| `default` | 该块的**原生标题色** | 不是固色：MEANING/MEMORY 等原生就是 `primary`，EXAMPLE 是 `onSurfaceVariant` |
| `primary` | `primary` | |
| `secondary` | `secondary` | |
| `accent` | `tertiary` | |
| `muted` | `onSurfaceVariant` | 想要「像正文」的标题用它 |
| `warning` | `error` | |

> `default` 表示**不覆盖**。默认清单里每个块都是 `default`，正是靠这一条，1.4.1 的渲染结果才能一像素不动。

`size` 只作用于**内容文字**；块标题恒为 `labelLarge`（可改颜色，不可改字号）。它不是一个绝对字号，而是**相对该块原生正文的倍率**：

| size | 效果 |
|---|---|
| 缺省 / `M` | 原样，一像素不动 |
| `S` | 原生正文 × `0.9`（`fontSize` 与 `lineHeight` 同时乘） |
| `L` | 原生正文 × `1.15` |

为什么是倍率而不是「S=bodySmall / M=bodyMedium / L=bodyLarge」：各块的原生正文本来就不同档（MEANING 用 `bodyLarge`、MEMORY 用 `bodyMedium`、EXAMPLE 用 `bodySmall`），若统一映射成绝对档位，默认清单只要写一次 `M` 就会把外观从 1.4.1 挪开。倍率法则下「不改就是不变」天然成立。

---

## 4 容错（手机端必须满足）

按顺序取，取到就停：

1. **HTTP 拉取**（地址来自设置页，默认局域网，可改任意 URL）— 超时 5s
2. **本地缓存** `filesDir/cardback.json`
3. **上一份成功的** `filesDir/cardback.lastgood.json`
4. **内置默认**（编译进 App 的那份 = 当前卡背）

任何一步失败都**静默往下掉**，只在用户主动点刷新时给一句提示。

判定「这份 JSON 能用」：解析成功 + `schemaVersion <= 1` + `blocks` 非空 + 至少有一个块能被渲染。
任一不满足 = 拒收，保留原样。

**永远不出现空卡背。**

---

## 5 动效参数（与 `docs/decisions-1.4.2.md §4` 对齐）

| 字段 | 默认 | 含义 |
|---|---|---|
| `titleDurationMs` | 820 | 标题入场时长 |
| `staggerGapMs` | 90 | 条目错峰间隔 |
| `entryDurationMs` | 220 | 条目入场时长（easeOutExpo） |
| `overshootPercent` | 72 | 关键帧百分比位置 |
| `overshootPx` | -1.0 | 在该位置的位移（先冲过再回落） |
| `overshootScale` | 1.0015 | 在该位置的缩放 |

数值必须是有限数；除 `overshootPx`（**允许负数**，它本来就是\"先冲过去再回来\"）外，其余都要 `> 0`。
`overshootPx = 0` 视为无效（等于把反冲关掉，想关就删掉这行用默认值）。越界钳到边界，不报错。

---

## 6 电脑端本地服务（HTTP API）

- 监听 `0.0.0.0:8765`
- 零第三方依赖（Python 3 标准库）
- 启动后打印局域网地址，形如 `http://192.168.1.23:8765/cardback.json`
- 所有写操作只落在 `tools/cardback-editor/published/` 下

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 编辑器页面（`index.html` / `styles.css` / `app.js` / `samples.js`，按扩展名白名单放行） |
| GET | `/cardback.json` | **手机拉的就是这个**，返回已发布配置 |
| GET | `/api/state` | `{ok,revision,updatedAt,publishedAt,lanUrl,historyCount}` |
| POST | `/api/publish` | body = 完整配置。服务端校验 → 自己定 `revision`（`+1`）→ 旧的进 history |
| GET | `/api/history` | `{ok,items:[{revision,savedAt}]}` |
| POST | `/api/rollback` | `{revision:n}` 把某版恢复为当前 |

**归属与语义（实现口径，以此为准）**：
- `publish` **忽略 body 里的 `revision`**，版本号由服务端给，客户端算不出并发安全的自增。
- `rollback` 是\"把某一版重新发布\"：**先把当前版归档进 history，再写回目标版**，所以 `historyCount` 会涨，回退动作本身也可回退。
- `savedAt` 用的是**归档时间**（这一版进 history 的时刻），不是它最初被发布的时刻。
- 发布成功返回新 `revision`；手机端只在 `revision` 变大时才替换本地清单。

失败一律 `{"ok":false,"error":"人话原因"}`，HTTP 400；成功 `{"ok":true,...}`。
读写磁盘异常（目录不可写、JSON 损坏）返回 HTTP 500，不吞异常。

发布时服务端要**再校验一遍**：`blocks` 非空、`id` 不重复、每个 `type` 是字符串。任何一条不过就拒绝，并把原因回给编辑器（指明是第几个块、哪个字段）。
**未知 `type` 不在拒绝之列**：它被原样保存、在响应里以 `warnings` 提示\"这个块手机端会跳过\"——因为 1.4.2 的手机端遇到不认识的块就是跳过而不会崩，拒绝反而会让用户改一个字就没法发布。

---

## 7 编辑器行为

- 左：组件库（可添加 / 显示隐藏 / 改标题 / 改颜色 / 改字号）
- 中/右：**实时预览**，用一份内带示例词渲染，块顺序和手机一致
- 底部：`保存草稿` / `发布到手机` / `回退到上一版`
- 拖动改顺序（纵向列表，不是自由画布）
- 预览与真机**不可能像素级一致**：结构、顺序、文案必须一致；颜色字号以手机为准
- 编辑器里不出现任何「手机上没有的功能」

---

## 8 版本

- 手机端：`versionCode 5` / `versionName 1.4.2`（**最后一步才改**）
- DB 不动：保持 `14`
- 卡背内容与 `tb_progress` 完全解耦
