# 1.4.3 重写记忆思路 · 第三轮复评（只读）

- 评审时间：2026-11-07 13:30 前后
- 评审方式：只读代码 + 只读接口（`GET /models`、`GET /rewrite/status`、`GET /rewrite/results?since=0&limit=200`），未修改任何实现文件，未运行会改状态的命令
- 证据来源：`app/src_repo/...`、`tools/rewrite-service/`、`tools/cardback-editor/server.py`、`docs/spec-1.4.3.md`、`tools/rewrite-service/data/`（queue/state/results/logs）、`git status`
- 纪律：**以代码为准**。父 agent 的 6 条改动自述只作为待核实清单，文档自述单独在 §七 对账

---

## 一、结论速览

| 维度 | 满分 | 本轮 | 上轮 | Δ |
|---|---|---|---|---|
| 需求符合 | 40 | **36** | 34 | +2 |
| 正确与健壮 | 25 | **21** | 18.5 | +2.5 |
| 代码卫生 | 20 | **17.5** | 17.5 | 0 |
| 安全 | 10 | **8.5** | 9 | −0.5 |
| 文件卫生 | 5 | **3.5** | 3.5 | 0 |
| **合计** | 100 | **86.5** | 82.5 | **+4** |

一句话：上轮 4 项 P0 里**3 项真改好了**，B4 的守卫方向确实拨正了并且能被服务端数据反证；step5 空 content 的根因诊断正确，`generate.txt` 缩短后三次真实运行都出了内容。但第 4 项 P0（提交 + 目录卫生）**没做**，自述「4 项 P0 已改完」与仓库状态不符；本轮还**新引入**了一处前后矛盾的输出契约（`generate.txt` 与 `pipeline.OUTPUT_SHAPE` 各说一套），而新打开的「换机/重装恢复」路径**没有任何实测证据**。

---

## 二、六条改动逐条核实

| # | 自述 | 代码位置 | 判定 |
|---|---|---|---|
| 1 | `posOf(meaning)` 抽词性，不再写死 `pos=""` | `RewriteRepository.kt:281` 调用、`:301-305` 实现 | **真改**（代码 + 服务端双证） |
| 2 | `applyResult` 三层认领守卫 | `RewriteRepository.kt:224-232` | **真改，方向正确，未发现新绕过**（见 §四） |
| 3 | `finish_fail` 加 superseded 守卫 | `store.py:239-243` | **真改**，与 `finish_ok` 对称 |
| 4 | `generate.txt` 缩成「只产三行推导」 | `prompts/generate.txt`（6026B → 3566B，13:01:28） | **真改，且有实测背书** |
| 5 | `data/` 整目录清空重建 | `data/queue.json`、`state.json`、`results/` | **真改**（无 probe、seq 归零） |
| 6 | 启动同步搬到学习页 resume，每轮按库刷新 | `KaoyanWordApp.kt:112-113`、`LearningScreen.kt:171-174`、`LearningViewModel.kt:381-397` | **真改，逻辑正确** |

细节：

1. **词头补齐**：`upload()` 现在逐条查 `wordDao.getWordById` 与 `wordMemoryDao.getByWordId`，`phonetic/meaning/currentApproach` 全带上（`:280`、`:282`、`:286-290`）。`posOf` 用 `POS_PREFIX = Regex("^([a-zA-Z]{1,5}(?:\\.[a-zA-Z]{1,5})?)\\.")`（companion，`:397`）从释义前缀抽词性；`adj./v./vt./prep.` 都取得到，`n./v.` 取到第一个 `n`，`（adj.）…` 这种带括号开头的取不到→空串（可接受）。服务端 `queue.json` 三条实测均为 `"pos": "adj"`，`_brief` 里因此有「词性：adj」一行。
2. **守卫三层**：见 §四，逐分支验证 + 三个被点名角度的绕过分析。
3. **`finish_fail`**：`if live.get("superseded"): live.update(state="failed", note="被同词新标记覆盖，失败记录丢弃", …); self._save_queue(); self._cond.notify_all(); return None`。唯一不对称：superseded 项终态记 `failed`（`finish_ok` 记 `done`），只影响面板显示，无害。
4. **提示词缩短**：`service.log` 13:01:44 起三次真实运行，step5 两次生成分别 25.6s / 19.3s，均产出内容（此前 11:36–11:47 有三次 `空 content（finish_reason=length）`）。第一轮拆分 0 分被拒、第二轮 10/10/10/8/9 通过，符合「两次评分」预期。
5. **数据重置**：`state.json {"seq":3}`；`results/appropriate.json` 三条 `ok`，clientId 依次 `w1-1794028128103 / w1-1794028485984 / w1-1794029116816`；`/rewrite/results?since=0` 实测三条全是真实格式，**probe 数据已不存在**。
6. **同步时机**：`Application.onCreate` 里的同步已删（只留注释说明原因），学习页 `LifecycleResumeEffect` 触发；`syncRewrite()` 在 `sync()` 返回后**重读** `_currentWord.value` 再按库比对刷新（`:386-391`），不再依赖 `touchedWordIds`。小瑕疵见 §五 新-6。

---

## 三、上一轮 4 项 P0 结账单

| P0 | 内容 | 状态 |
|---|---|---|
| P0-1 | `meaning` 前缀抽 `pos`，别写死空串 | **已修** |
| P0-2 | 守卫改「mark==null→接受；mark!=null→只在 clientId 匹配时接受」 | **已修**（改的是接受条件，不是叠加 if） |
| P0-3 | 清 `tools/rewrite-service/data/`，与修恢复同一次提交 | **已修**（数据清了；但「同一次提交」这个前提落空，因为根本没提交，见 P0-4） |
| P0-4 | `git add tools/rewrite-service/` + 清 `docs/wb-gateway-repair-20261107/` | **未做** |

P0-4 现状（`git status --short`）：

```
?? tools/rewrite-service/
?? docs/wb-gateway-repair-20261107/
?? docs/spec-1.4.3.md
?? docs/test-report-1.4.3.md
?? docs/review-1.4.3.md
?? docs/review-1.4.3-round2.md
```

也就是说 1.4.3 的**全部交付物**（服务端 + 4 份文档）都还在「未跟踪」里，工作区里任何一次 `git checkout/clean` 都能把它们抹掉。本轮做对了一半：`.gitignore:44` 加了 `tools/rewrite-service/data/`，服务目录自带 `.gitignore` 忽略 `__pycache__`，`git ls-files -o --exclude-standard tools/rewrite-service/` 现在只剩 11 个该提交的文件（源码 + prompts + panel + bat + README）——**只差按下这一下**。

---

## 四、守卫绕过分析（父 agent 点名的三个角度 + 两条补充）

现码（`RewriteRepository.kt:224-232`）：

```kotlin
val mark = queueDao.getByWordId(wordId)
val incoming = dto.clientId?.takeIf { it.isNotBlank() }
if (incoming != null) {
    val owner = CLIENT_ID.matchEntire(incoming)?.groupValues?.get(1)?.toLongOrNull()
    if (owner == null || owner != wordId) return null
    if (mark != null && incoming != clientIdOf(mark)) return null
} else if (mark == null) {
    return null
}
```

| 角度 | 结论 |
|---|---|
| 大小写 | `CLIENT_ID = Regex("^w(\\d+)-(\\d+)$")`（`:394` 附近）+ `matchEntire`，无 IGNORECASE。`W1-1` 被拒；手机端只产生小写，攻击者用大写只会更早被拒。**无绕过** |
| 多条同词 | 现存数据本身就是同词三条（seq1/2/3）。有标记时只认当前 clientId（`:229`）✅；无标记（重装）时三条按 seq 升序依次落库，rev 1→2→3 ✅。`getWordIdByKey` 的 `LIMIT 1` 因 `WordEntity.kt:10` 的 `Index(["word_key"], unique = true)` 而确定，不会因同词多行误杀 owner 校验 ✅ |
| seq 前进但被丢弃 | `sync()` 在 `:181-185` 无论结果是否被守卫丢弃都会推进 `lastSeq`。对「迟到/别家的」结果这是对的（丢一次就该永丢）。副作用是**静默且不可恢复**：无日志、无 UI 痕迹，排查只能拿服务端 seq 反推。建议丢弃时 `Log.w` 一行 |
| 空 clientId（补充） | `incoming == null && mark != null` → **直接接受**（`:230-232` 的反面），不校验任何东西就覆盖卡背正文。今天不可达（`store.py:135-137` 强制 clientId，老数据已清），是死代码；但 8765 无鉴权，将来谁放宽 `add_marks` 它就变成内容注入路径。建议收紧成 `else { return null }` |
| wordId 漂移（补充） | 换书/删书导致 wordId 变化时历史结果全被丢。词都没了，可接受 |

**结论：没有发现新的绕过路径**；上轮「换机恢复被守卫打死」确实修好了。代价是恢复路径把「无标记时信任服务端」写进了设计（见 §六 信任边界）。

---

## 五、新引入 / 变真实的问题

### 新-1（重要）`generate.txt` 与 `pipeline.OUTPUT_SHAPE` 互相矛盾

- `prompts/generate.txt` 新 schema：`"split": ["元素1","元素2"]`（数组）、`"elements": [{"part","method","reasoning"}]`（对象数组）
- `pipeline.py:31-40` 的 `OUTPUT_SHAPE` 仍然在**每次生成**时追加在 user 消息末尾（`pipeline.py:111`），里面写的是 `"split": "…（短语）"`（字符串）、`"elements": ["…"]`（字符串数组）
- 后果：一次请求里两套 schema，模型随机二选一；`pipeline.py:125` `str(draft.get("split"))` 拿到数组时得到 `"['ap', 'propri']"`，这个 Python repr 又被拼进润色提示词（`:136-139`）当「拆法」——把一份没人看得懂的字符串喂给润色模型
- 为什么是问题：这是本轮唯一「提示词说 A、代码按 B 解析」的地方，且是**本轮改动直接引入**（`pipeline.py` 12:19 未动，`generate.txt` 13:01 被重写，追加逻辑就悬空了）。样本只有一个词，换词后形状一变就开始污染润色输入
- 改法（2 行）：删 `user += OUTPUT_SHAPE`，或把它换成新 schema；`:125` 加 `isinstance` 判断后 `" / ".join(...)`

### 新-2（重要）「换机/重装恢复」只有代码，没有实测

- 三次实测都是**同一台已装 App**（队列里始终有标记），`mark == null` 这条新分支一次都没走到
- 未验证：清数据后 `since=0` 是否按序写回三条、`rev` 是否落到 3、`source` 是否 `user`、撤销是否还可用
- 为什么是问题：这是上轮唯一被判「逻辑反了」的路径，改完没在任何干净设备上跑过就装机，等于让用户替你测

### 新-3（次要）撤销与服务器 `rev` 分叉

- `undo()` 删队列行（`:135`）且**不通知服务端**，服务器 `_current(record).rev` 仍是 3
- 重装后 `since=0` 重放三条 → rev 回到 3、`prev_approach` = seq3 的 prev（step5 那版）→ 用户撤销掉的改写**复活**
- 撤销后重标记：服务端算 rev=4、`prev`=rev3 文本，而手机显示的是 rev2 文本 → 再点撤销会跳到用户明确否掉的那一版
- 结论：撤销只在「单机单次」语义下成立。本轮建议按 §八 写进已知限制，不在 1.4.3 实现 rev 回退同步

### 新-4（次要）恢复时会把 failed 数成「失败提示」

- `applyResult` 对 failed 且无队列行时 `updateStatus` 是 no-op（`RewriteQueueDao.kt:34-38` 是裸 `UPDATE … WHERE word_id`），但 `:178` 仍 `failed += 1` → 首启可能弹「N 条失败」。当前 0 failed 未暴露

### 新-5（次要）judge 输入裁剪靠字符串手术

- `pipeline.py:158` `_brief(item).split("旧版思路")[0]`：用户 instruction 里一旦出现这四个字就会连带切掉词头。概率低（instruction ≤200 字），但它和上轮「judge 只给三行」是同一处老毛病，改成显式拼字段更稳

### 新-6（次要）引用比较判「模型列表未加载」

- `LearningViewModel.kt:393` `if (_rewriteModels.value === RewriteModels.DEFAULT)`：靠引用相等判断「还是兜底值」，将来谁 `copy()` 一下就会永久跳过 `/models`

---

## 六、安全：0.0.0.0 无鉴权，这次怎么说才算诚实

现状：`server.py:38 HOST = "0.0.0.0"`，无 token、无来源校验。同网任何设备可以：读 `/rewrite/` 面板与全部改写结果；`POST /rewrite/marks` 提交任意标记；`POST /rewrite/retry` 让电脑重复调用付费模型（这一条连 clientId 都不用）。

为什么这次可以不改：家用 LAN、单人、8765 本来就同时对外提供编辑器与面板；加鉴权要动手机端地址配置与全部调用点，属 1.4.4 的活。

但文档必须写，且不能只写「手机与电脑必须同一局域网」（`spec:227` 现在只有这一句）。建议直接粘进 `spec §7`：

- 电脑端服务监听 `0.0.0.0:8765`，**没有鉴权**，只用于可信的家庭/个人局域网。同网任何设备都能看到面板与全部结果、替任意词提交重写标记、通过 `/rewrite/retry` 让电脑重复调用付费模型；手机端只认「格式为 `w{wordId}-{时间戳}` 的 clientId」，这是**防错认**，不是**防伪造**。
- 不要把 8765 暴露到公网；连陌生 Wi-Fi 时不要开着它。跨网使用需要先加反向代理与 token（未实现）。
- 恢复路径的信任边界：重装/换机后手机没有本地标记，此时它**无条件接受**服务端给出的、clientId 归属本词的结果——服务端被谁控制，卡背正文就是谁说了算。

---

## 七、文档与代码不一致

| # | 位置 | 文档说 | 代码/事实 |
|---|---|---|---|
| 1 | `spec:117` | `generate.txt # 六脉神剑（原样，来自 MindForge mnemonic-reference.txt）` | 已重写为 3.5KB 定制提示词，不再「原样」 |
| 2 | `spec:143` | 生成只要求 `{method, split, elements, derivation}` | 每次还在 user 尾巴追加 `OUTPUT_SHAPE`（`pipeline.py:111`），且其中 schema 与 `generate.txt` 相反（新-1） |
| 3 | `spec:185` | 结果认领「只认当前这条标记的 clientId」 | 还有第三分支：无标记 + 合法 clientId 时**接受**（`:230-232`）。`spec:34`/`:84` 的「since=0 换机恢复」正靠它成立，两处必须并成一句 |
| 4 | `spec:8`、`spec:215` | 「下次冷启动…写回」 | `spec:184` 与 README 已改成「同步只在学习页 resume」，验收 #2 的措辞没跟上 |
| 5 | `spec:153`、README | grok「串行连打则稳定／可用」 | 证据只有一次性成功（604s，且发生在 data 重置**前**的 probe 阶段）。重置后 5 个模型只复测 ds-v41-flash×2 + step5×1 → **验收 #8「五模型全部跑通」本轮无新证据** |
| 6 | `spec:46` | `clientId: "uuid-v4"`、`spec:49` `pos: "v."` | 实际是 `w{wordId}-{ts}` 与 `adj`。clientId 格式现在是**硬约束**（守卫 `matchEntire`），契约必须写成必填项，否则按文档实现的新客户端结果会被静默丢弃。另：spec 自己的例子 `pos: "v."` 与 `meaning: "v. 放弃；抛弃"` 是同一份信息写两遍（代码正是从 meaning 前缀推 pos），契约里应注明「pos 可为空，服务端不依赖它」 |
| 7 | `docs/test-report-1.4.3.md`（12:12） | — | 本轮三次真实运行的证据只在 `data/logs/service.log` 与 README 里，验收报告没更新 → 交付物缺一份可复核记录 |

---

## 八、能不能交付

**能装机试用（灰度），不能按 1.4.3 封版。**

理由：手机端 + 服务端已在本轮真实标记上跑通端到端（`done 3 / failed 0`，写回 rev=3、撤销回 seed/rev=0），数据面已经干净到**不会把测试数据灌进用户库**，守卫方向正确。剩下的缺口都不是「装上就坏」，而是「装上之后有一部分能力是未验证或未交代的」。

**装之前必须做完的最小集合（3 件，分钟级）：**

1. **修新-1**：删掉或改写 `pipeline.OUTPUT_SHAPE`，并让 `pipeline.py:125` 兼容数组。否则下一次换词的润色输入就带着 `['ap', 'propri']`。
2. **提交**：`git add tools/rewrite-service/ docs/spec-1.4.3.md docs/test-report-1.4.3.md docs/review-1.4.3*.md`，删掉或忽略 `docs/wb-gateway-repair-20261107/`。不是洁癖：**换机恢复依赖服务端 `data/` 的语义，而 `data/` 不入库**，交付物里必须有「重装后靠什么恢复」的可复现说明，否则下一个人接手时这条路径不可复现。
3. **补 spec**：§六 那三段（无鉴权、信任边界、恢复边界）+ §七 表里第 3、4、6 条（clientId 格式、同步时机、撤销不跨设备）。

**封版前还要补（不是装机阻断）：**

- 恢复场景实测一次：清 App 数据 → 进学习页 → 三条按序写回、rev=3、source=user、撤销可用（顺便确认「撤销不跨设备」是真限制）。
- `glm53` / `portdan-gpt` / `portdan-grok` 各跑一条真实标记，验收 #8 才有证据；按现状建议在模型下拉或 README 标注「grok 约 10 分钟、GPT/Grok 常被判不过」。
- 新-3 要么实现（撤销时通知服务端 rev 回退），要么写进 §7 已知限制——建议后者。

---

## 九、证据附录

`GET /rewrite/status`（节选）：

```json
{"pending":0,"running":0,"done":3,"failed":0,"todayDone":3,"todayFailed":0,"seq":3,
 "concurrency":2,"startedAt":"2026-11-07T13:01:44+08:00","judge":"wb/cn:glm-5.3"}
```

`GET /rewrite/results?since=0&limit=200`：`nextSince 3, more false, n 3`

```
1 appropriate rev 1 ok clientId= w1-1794028128103
2 appropriate rev 2 ok clientId= w1-1794028485984
3 appropriate rev 3 ok clientId= w1-1794029116816
```

`data/logs/service.log`（全文 18 行，节选）：

```
13:09:15 [appropriate] 生成 25.4s
13:10:49 [appropriate] 第1轮 润色 71.0s 评分 23.3s 最低 9 分 {"拆分成立":10,"方法选对":9,"谐音过关":10,"链条闭合":9,"文本充足":9}
13:15:13 [appropriate] 生成 25.6s
13:16:35 [appropriate] 第1轮 润色 68.0s 评分 14.2s 最低 0 分 {"拆分成立":0,"方法选对":2,...}
13:16:55 [appropriate] 生成 19.3s
13:18:20 [appropriate] 第2轮 润色 61.3s 评分 23.5s 最低 8 分 {"拆分成立":10,"方法选对":10,"谐音过关":10,"链条闭合":8,"文本充足":9}
13:25:52 [appropriate] 生成 33.4s
13:27:14 [appropriate] 第1轮 润色 71.3s 评分 11.1s 最低 9 分
```

（三条 `完成 seq=1/2/3 rev=1/2/3`，总耗时 119.6s / 212.0s / 115.8s；`queue.json` 三条 model 依次 `ds-v41-flash / step5 / ds-v41-flash`，`state=done`，`pos=adj`。）

`git ls-files -o --exclude-standard tools/rewrite-service/`（若现在 add，会进库的文件）：

```
.gitignore  README.md  panel.html  pipeline.py  prompts/generate.txt
prompts/judge.txt  prompts/polish.txt  providers.py  service.py  store.py  启动重写服务.bat
```

**上轮次要项仍未改（沿用上轮记录，不重复扣分）：** `judge(item, draft, approach)` 的 `draft` 形参仍未使用（`pipeline.py:154`）；`server.py:1010-1011` 仍是 `sys.path.insert` + `import service` 的通用模块名；`service.py:243` 独立调试模式（8799，仅回环）仍无 `Content-Length` 上限（8765 主路径已由 `server.py:854` 的 `MAX_BODY = 4MB` 兜住）；`WordMemory` 的 `source/rev` 仍无 `@ColumnInfo(defaultValue=…)`（`WordMemory.kt:37`、`:39`）；`RewriteRepository.kt:157` 存在 `if (pending.isNotEmpty()) {            val accepted` 的换行错乱。
