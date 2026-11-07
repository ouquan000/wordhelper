# 1.4.3「重写记忆思路」第二轮独立复评

- 复评日期：2026-11-07 12:50–13:10
- 复评性质：只读。未修改任何实现文件，未运行任何改状态的命令；仅 curl 了只读接口 `/models`、`/rewrite/status`、`/rewrite/results`。
- 上一轮：`docs/review-1.4.3.md`（79.5/100）
- 本轮对象：1.4.3 的「已修清单」，逐条以代码为准核实。
- **本报告与旧报告并存，不覆盖。**

---

## 0. 总评

| 维度 | 权重 | 上一轮 | 本轮 | 变化 |
|---|---|---|---|---|
| 需求符合 | 40 | 32 | **34.0** | +2.0 |
| 正确与健壮 | 25 | 16.5 | **18.5** | +2.0 |
| 代码卫生 | 20 | 17 | **17.5** | +0.5 |
| 安全 | 10 | 10 | **9.0** | −1.0 |
| 文件卫生 | 5 | 4 | **3.5** | −0.5 |
| **合计** | 100 | **79.5** | **82.5** | **+3.0** |

一句话判断：**上轮 8 条「已修」里，6 条是真修好了，1 条只修了一半（A1 的 pos），1 条修出了新洞（B4 的认领守卫方向装反：既打破了「换机/重装全量恢复」，又对 17 条没有 clientId 的老结果形同虚设）。** 分值提升主要来自 B1/ B3/ B5/ 评委截断兜底这几处真实的健壮性改善；扣分来自新引入的恢复回归、仍然没解决的 step5/grok 可用性，以及安全/文件卫生本轮才被翻出来的老问题（不是本轮新引入）。

**结论先说：能装机试用（单人自用、默认 `ds-v41-flash`），但装机前必须先修 §5 的 4 项 P0（预计 30 分钟内，其中两项是「修恢复逻辑 + 同一次清掉测试数据」，必须一起来）；不能按 1.4.3 封版，因为验收 #8「5 个模型全部跑通」实测未达成，且 §2 的换机恢复已被自己的修复打破。**

---

## 1. 上轮「已修」清单逐条核实（以代码为准）

图例：✅ 真修好 ｜ 🟡 部分修好 ｜ ⚠️ 代码在但效果未证 ｜ ❌ 没改

| # | 声称的修复 | 核实结果 | 证据（文件:行） |
|---|---|---|---|
| 1 | A1 上传补齐词头与 currentApproach | 🟡 **部分**：phonetic/meaning/currentApproach 已补，`pos` 仍恒为空串 | `RewriteRepository.kt:266-282`，其中 `:271` phonetic、`:273` meaning、`:277-281` currentApproach、**`:272` `pos = ""`** |
| 2 | B1 `_prune` 保留最新 | ✅ 真修好 | `store.py:358-369` 改成 `for item in reversed(self.items)`；`:19` `KEEP_FINISHED=500`；两个调用点 `:227` `:254` 后都跟 `_save_queue()` |
| 3 | B2 评委只给成品三行 | ✅ 真修好（附带副作用） | `pipeline.py:157-160` `_brief(item).split("旧版思路")[0]` + 成品三行；不再传 method/split/elements |
| 4 | B3 QUEUED 可重发 + 30 分钟挂回 PENDING | ✅ 真修好 | `RewriteBar.kt:81-87` `else -> onMark(...)`；`RewriteRepository.kt:150-154` + `STALE_QUEUED_MILLIS`(`:376`)；`RewriteQueueDao.kt:28-29` SQL 限定 `status='queued'`，不会误翻 done/failed |
| 5 | B4 clientId 认领守卫 + 服务端结果写 clientId | 🟡 **修了但有两个洞** | 服务端 `store.py:220` / `:248` / `_public():71` 已写 clientId；手机端 `RewriteRepository.kt:221-223` 有守卫。洞见 §3-重要-1/2/3 |
| 6 | 评委 max_tokens 3000→8192 + 截断兜底 | ✅ 代码就位，修后未复发（样本有限） | `pipeline.py:166` `max_tokens=8192`；`:170-183` 首次解析失败 → 追加「issues 最多 2 条」再问一次。失效现场在修前（12:08:51 那条 `评分结果不是 JSON：{"scores":{...9...},"pass":true,"issues":[...`），`pipeline.py` mtime 12:19:41 晚于它；修后 12:20–12:49 共 6 轮评分全部解析成功 |
| 7 | 中转站 grok 加进程内串行锁 | ⚠️ 代码就位，**未证有效** | `providers.py:29` `_PROVIDER_LOCKS = {"portdan-grok": threading.Lock()}`、`:242-248` 重试循环内 `with lock: return _call(...)`。但锁落地（mtime 12:37:21）之后只跑了 1 条 grok（成功，604s），锁前 3 条全是 `HTTP 502` |
| 8 | 手机端重写条只在卡背显示 | ✅ 真修好 | `LearningScreen.kt:592-605` `if (isCardFlipped) { RewriteBar(...) }`，位于卡片与 `RecognitionActionPanel` 之间；`:582` 更新、`:186/:196/:206/:1649` 复位 |

补充正向确认（本轮实测，不是自述）：

- `/models` 与 `/rewrite/status` 经 8765 转发可用，UTF-8 端到端无乱码（用 Python 解字节确认，不是看控制台）。
- `docs/spec-1.4.3.md:34/49/84` 的契约与 `providers.MODELS`、`RewriteModel.kt` 的 5 项逐字一致。
- `prompts/judge.txt:6-12` 的 5 个维度名与 `pipeline.py:22` `JUDGE_DIMENSIONS` 逐字一致（维度名不一致会让 fail-closed 把每个词都判 0 分，这里没有这个坑）。
- `MIGRATION_14_15` 用 `ensureColumnExists` + `DEFAULT 'seed'/'0'` + 唯一索引，老数据不会因为新列 NOT NULL 而迁移失败。
- `tools/rewrite-service/data/` 与 `__pycache__` 已被 `.gitignore:44` 和 `tools/rewrite-service/.gitignore:1` 覆盖（上轮 C4 真修好）。

---

## 2. 评分明细

### 需求符合 34/40

达成：卡背重写条（含模型下拉、折叠输入框、状态小字、撤销）、手机只打标记不调模型、离线队列 + 补发、`source/rev/prev` 写回与撤销、种子导入跳过 `source=user`（`WordRepository.kt:1038-1042`）、独立评委、两轮重试、`/rewrite/status|results|word|retry` 全套。

扣分：
- **−3** 验收 #8「下拉 5 个模型全部跑通」未达成。线上 `queue.json` 按模型统计：`ds-v41-flash` 9 成 1 败、`step5` 2 成 **4 败**（全是 `空 content（finish_reason=length）`）、`portdan-grok` 1 成 **3 败**（全是 `HTTP 502`）、`portdan-gpt` **0 成 2 败**（两轮评审都没过）、`glm53` 1 成 1 败。独立测试报告 §6.8 自己也判了 FAIL。
- **−1.5** §2「换机/重装：手机拉一次全量历史即可恢复（since=0）」被 B4 的认领守卫打破（`spec:34`、`spec:84` 与 `RewriteRepository.kt:221` 直接冲突）。
- **−1.5** 词头 `pos` 恒空（`RewriteRepository.kt:272`），与 `spec:49` 的 marks 契约不符；连带 `_brief`（`pipeline.py:91-103`）里「词性」那一行**永远不出现**，生成模型少一条上下文。

### 正确与健壮 18.5/25

修好：`_prune` 方向、QUEUED 死锁、缺维度按 0（`pipeline.py:192-194`）、评委截断兜底、grok 锁。

扣：
- **−1.5** 换机/重装恢复回归（数据在电脑端但手机永久看不到，且 `since` 照常前进 → 不可重取）。
- **−0.5** 认领守卫方向装反（无 clientId 全放行、无队列行全拦），且「修好恢复」会立刻把 24 条测试/旧结果灌进用户手机 —— 两个 bug 互相掩护，改一个暴露另一个。
- **−1** step5 在 `max_tokens=12000` 下仍稳定返回空 content（同一词 3 次），说明 §4.5 与 `README:245` 的「把 max_tokens 放大就行」这条处方**无效**，没有第二层兜底。
- **−1** grok 502 未根治（锁不是解药，是症状管理）。
- **−0.5** `finish_fail` 缺 superseded 守卫，与 `finish_ok` 不对称。
- **−0.5** `service.py:243-244` 读 `Content-Length` 无上限。
- **−0.5** `sync()` 上传失败即 `return`（`RewriteRepository.kt:158`），连结果都不拉；`applyResult` 对「词不在当前书」的条目静默丢弃（`:216`）。

### 代码卫生 17.5/20

修好：`remember(wordId)`（`RewriteBar.kt:59-60`）、注释里保留用户原话（`pipeline.py:5-7`「不能又当选手又当裁判」）、`pipeline.py:29-30` 解释了为什么不改逐字原文而是运行时追加形状。
扣：`judge(item, draft, approach)` 的 `draft` 已成死参（`pipeline.py:154`）、`server.py:1003-1017` 仍是 `import service as rewrite_service`（上轮 B9 未改）、`RewriteRepository.kt:157` 一行里塞了 `if (pending.isNotEmpty()) {            val accepted = ...`、`pos = ""` 无注释说明原因、`generate.txt` 每次调用都带 6KB 生图全文（`C1` 未消，只是被 `OUTPUT_SHAPE` 遮盖）。

### 安全 9/10

- 优点：凭据只在运行期读 `~/.dsh/.credentials.yaml`，缺失直接抛错，不回落硬编码；`WB_KEY` 是本地占位；日志里不打印 key。
- **−1** `server.py:38` `HOST = "0.0.0.0"` 且全仓无任何鉴权（grep 无 token/Authorization）。`:534` 的 `_is_lan_ip` 只用于**探测并展示**局域网地址，**不是访问控制**。1.4.2 时暴露的只是卡背读写；1.4.3 把「花真钱调大模型」的接口挂到了同一个端口，任何能连到 8765 的设备都可以 `POST /rewrite/marks`（单次 200 条）烧额度，并 `GET /rewrite/results` 读走全部记忆思路正文。
- −0.5 已并入健壮维度，不重复扣。

> 说明：这条是**上轮漏掉的老问题**，不是本轮回归，所以只扣 1 分；但必须在「局域网里还有别人」的前提下当红线看。

### 文件卫生 3.5/5

- ✅ `data/`、`__pycache__` 已忽略。
- **−1** `docs/wb-gateway-repair-20261107/` 仍然 `??` 且未被 ignore（上轮 C3 未修）。
- **−0.5** `tools/rewrite-service/` **整个目录 untracked**（`git status` 显示 `?? tools/rewrite-service/`）。这是电脑端实现主体，交付前不纳入版本库等于交了一份随时会丢的代码。

---

## 3. 问题分级

### 阻断（交付前必须处理）

**无「App 装上去就崩/丢数据」级别的阻断缺陷。** 主路径（默认模型、单卡重写、断网补发、撤销）都能走通，所以 App 本身没到「不能装」的地步。但这轮复评在 B4 的修复里找到一条**会让用户丢数据的回归**（重要-1/2/3 是同一个病根的三段），它属于「装了能把东西弄丢」，按交付标准要拦。必须修的最小集合列在 §5 的 P0。

### 重要

**重要-1｜认领守卫把「换机/重装恢复」打死了**
`RewriteRepository.kt:221` `val mark = queueDao.getByWordId(wordId) ?: return null`。
为什么是问题：新装/重装后 `tb_rewrite_queue` 是空的，于是**每一条**历史结果都在这里被丢掉；紧接着 `:181-185` 仍然把 `since` 推进到最新。结果：用户在手机上永远看不到自己以前重写过的思路，而且电脑端数据还在、却没有任何路径能拉回来（除非手动清 DataStore 的 `rewriteLastSeq`）。这直接违反 `spec:34`/`spec:84` 明写的「since=0 全量恢复」，也是本轮修复**自己引入的新回归**。
最小改法：`mark == null` → 接受（老设备/全新设备的历史恢复）；`mark != null` → 只有 `incoming != null && incoming == clientIdOf(mark)` 才接受，**没 clientId 的老条目在有队列行时一并丢弃**（宁可少恢复一条，也不要覆盖用户刚发的新标记）。

**重要-2｜`clientId` 为空的条目无条件放行，而线上 24 条结果里有 17 条就是空**
`RewriteRepository.kt:222-223` `if (incoming != null && incoming != clientIdOf(mark)) return null`。
实测 `GET /rewrite/results?since=0&limit=200`：seq 1–17 **全部** `clientId: null`，seq 18–24 是 `probe-m5-glm` / `probe-m5-step5` / `probe-m5-gpt` / `probe-m5-ds` / `probe-m5-grok` / `probe-grok-2` / `probe-d2` 这类**测试探针**写的值，**没有一条**是手机端真实格式 `w{wordId}-{createdAt}`。
为什么是问题：这个守卫的方向正好装反了——**能验证的（有 clientId）它不管，不能验证的（null）它放行；该恢复的（没有队列行）它拦死，不该恢复的（有历史垃圾行）它照收**。所以它不是「有洞」，是「没起作用 + 副作用是阻断恢复」。危害面比重-1 更大：17/24 的历史条目一旦被重取（清过 `lastSeq`、或修好重要-1 之后做全量恢复）会无条件落到当前标记上，把用户刚重发的新标记标成 DONE 并写回一份旧思路。
最小改法：**没有 clientId 的一律按「历史恢复」处理（只在 `mark == null` 时接受），有 clientId 的只在匹配时接受**——即把 null 从「放行」改成「跟 mark 绑定」。

**重要-3｜生产结果流里混着测试探针数据，清不干净**
seq 18–24 的 `clientId` 是 `probe-m5-*` / `probe-d2`，`data/queue.json` 里也有 `probe-m5-*` 的 done/failed 项，`data/results/` 下每个词一个文件、同样带着这些条目。
为什么是问题：**修好重要-1（无队列行也接受）的那一刻，全量恢复就会把这 17 条旧结果 + 7 条探针结果一起灌进用户手机**，用户会在自己没标记过的词上看到「上次没成功」「已重写」的红字，甚至被探针写入一份 rev=3 的思路。现在这个隐患被「恢复失效」这个 bug 意外挡着，两个 bug 互相掩护，修一个就会暴露另一个。
最小改法：修重要-1 的同一次提交里，**先清掉 `tools/rewrite-service/data/`（queue.json / results/ / logs/），或给 /rewrite/results 加一个「只返回 `updatedAt >= 部署时间` 的条目」的门槛**，别让测试数据跟着代码一起交付。

**重要-4｜step5 在 12000 下仍然稳定空 content（验收 #8 的真正拦路石）**
证据：`data/queue.json` 里 `appropriate`×2、`increase` 全是 `wb/cn:step-5-preview 三次失败：空 content（finish_reason=length）`。`pipeline.py:116` 已经是 12000，说明 `spec §4.5` 和 `README:245` 的处方（「放大 max_tokens，6000 不够」）到此为止**无效**。
为什么是问题：不是「偶尔抽风」，是同一个词连试 3 次都空——推理型模型把预算烧在过程上，而 `prompts/generate.txt` 是 6KB 的生图/例句全文（`pipeline.py:29-30` 承认了这一点，用运行时追加 `OUTPUT_SHAPE` 来收窄输出）。**最可疑的根因是这份超长 prompt 本身**：模型先按原文写一大段带配图描述的内容，等不到 JSON 就被 length 截断，content 直接为空。
最小改法（二选一，不必都做）：① 给 step5 单列 `max_tokens=20000`；② 从 `generate.txt` 里物理删掉配图/例句段（服务本来就不要），这同时能省每次调用 2-3k token 的钱。**②更值得做**。

**重要-5｜`finish_fail` 缺 superseded 守卫，与 `finish_ok` 不对称**
`store.py:232-257` vs `:204-208`。
为什么是问题：用户改了指令重发（旧任务被标 `superseded`），旧任务随后失败时，`finish_fail` 没有 early-return，会**照样**写一条 `status:"failed"` 的结果条目、并把 `live` 置成 `state="failed"`。手机端的 clientId 守卫能挡掉这条结果（好消息），但 `/rewrite/status` 面板会把一条本该被丢弃的任务显示成失败，且 `results` 里多一条垃圾条目。写回逻辑两条路径必须同构，否则下一个改这两处的人一定会踩。

### 次要

| # | 位置 | 问题 | 为什么算问题 |
|---|---|---|---|
| 次-1 | `RewriteRepository.kt:272` | `pos = ""` 硬编码 | `Word` 实体本来没有 pos 字段（`data/entity/Word.kt`），所以不是笔误而是缺数据；但 `meaning` 里已带 `adj.`/`v.` 前缀（`content/seed/cet6_cards.json` 实测如此），一行正则就能补上，补上后 `_brief` 的「词性」行才会出现 |
| 次-2 | `pipeline.py:154` | `judge(item, draft, approach)` 的 `draft` 未使用 | 上轮为了修 B2 把 method/split 拿掉之后留下的死参；后来人读签名会以为评委还看得见草稿 |
| 次-3 | `pipeline.py:170-183` | 截断兜底是「整条重问」，没有「从截断文本里抢救 scores」 | 12:08 那次失败里 `{"scores":{...9 分全给齐...},"pass":true,"issues":[...` 已经完整，只因为尾部 `issues` 被截断就整条判废。解析失败时先 `re.search(r'"scores"\s*:\s*\{.*?\}')` 再 `json.loads`，能把这类词救回来，且不花第二次钱 |
| 次-4 | `pipeline.py:157-158` | 评委仍能看到 `instruction`（用户指出的问题） | `spec §4.4` 写「只看成品三行」；现在靠 `.split("旧版思路")[0]` 切在 instruction 之后，评委知道用户抱怨什么 → 有迎合倾向（用户说第三句牵强，评委就容易给「链条闭合」低分）。要么把 instruction 也切掉，要么在 spec 里承认这是有意给的 |
| 次-5 | `service.py:243-244` | 读 `Content-Length` 无上限 | 一个异常/恶意请求就能让服务端按几十 MB 分配内存；配合无鉴权，局域网里任何设备都能打 |
| 次-6 | `RewriteRepository.kt:158` | 上传失败直接 `return`，不拉结果 | 上传与拉结果共用一个 origin，通常同生共死，所以实际损失小；但语义上「上传失败」不该等于「不拉结果」，将来 origin 拆分就会变成 bug |
| 次-7 | `RewriteRepository.kt:216` | `getWordIdByKey` 找不到词就丢结果 | 词在别的书里、或书被卸载时会命中；结果静默丢弃且 `since` 已前进 → 同重要-1 的不可恢复问题 |
| 次-8 | `RewriteRepository.kt:376` | `STALE_QUEUED_MILLIS = 30min` 相对实测偏紧 | 实测单条 grok 604s（10 分钟），并发 2、同批发 3-4 个 grok 词时最后一条必然超 30 分钟 → 被翻回 `PENDING`，UI 上短暂显示「待上传」，若此时用户点取消会删掉行、结果回来后无处落。自愈路径存在（重传同 clientId 幂等接受），所以只是观感与误操作风险 |
| 次-9 | `data/entity/WordMemory.kt:37-42` | 新列没有 `@ColumnInfo(defaultValue=...)` | 迁移里写了 `DEFAULT 'seed'/'0'`，而 `AppDatabase.kt:64` `exportSchema = false` 且 Room 在实体侧默认值为 null 时跳过比较，**当前无风险**；但两边不一致，将来打开 `exportSchema` 或换迁移写法就会报 schema 校验失败（上轮 B10 的实际状态就是这个） |
| 次-10 | `cardback-editor/server.py:1003-1017` | `sys.path.insert` + `import service as rewrite_service` | 上轮 B9，仍未改。`service` 这个名字太通用，编辑器进程里任何别的 `service` 模块都会被抢注 |
| 次-11 | `docs/wb-gateway-repair-20261107/` | 另一任务的临时目录仍未被忽略/删除 | 上轮 C3，未修 |
| 次-12 | 整个 `tools/rewrite-service/` | untracked | 见 §2 文件卫生 |

---

## 4. 文档与代码不一致（本轮新记 4 条）

1. **`spec:49` 的 marks 契约含 `pos`，手机端恒发 `""`**（`RewriteRepository.kt:272`）。服务端 `store.py:169` 只是透传，`_brief` 见到空值就跳过「词性」行——契约上有一列，事实上永远是空。
2. **`spec:34` / `spec:84` 写「since=0 为全量，换机/重装恢复用」，代码已做不到**（`RewriteRepository.kt:221`）。这是本轮修复引入的实际回落，规格必须先改或代码必须改，不能两说。
3. **`README:245` 写「推理型模型要把 max_tokens 放大（step-5 给 6000 会返回空 content）」**，暗示放大即可。实测 12000 仍 3 次空 content，这句话现在是误导性的，应改成「step-5 在 12000 下仍会空 content，需另想办法」。
4. **`pipeline.py:3-6` 的模块 docstring 说「评分那一段只拿到『词 + 成品』」**，实际还拿到音标/释义/instruction（`pipeline.py:157-158` 切在 instruction 之后）。docstring 比代码严，属于自述比实现漂亮。

---

## 5. 能否交付装机试用？

**能装，但装之前必须修下面 4 项 P0（含一条要顺手清的数据），否则会有一次性的丢数据风险；也不能按 1.4.3 封版。** 试用口径：单人自用、默认 `ds-v41-flash`、别在 `step5` / `portdan-grok` 上浪费注意力。

### P0（装机前必修，30 分钟内）

1. **修 `pos`**：`RewriteRepository.kt:272` 用 `meaning` 的前缀正则补词性（`^\s*(adj|adv|n|v|vt|vi|prep|conj|pron|num|art|int)\.`），拿不到就留空。一行正则，顺带把契约补齐。
2. **修恢复路径**：`RewriteRepository.kt:221-223` 改成「`mark == null` → 接受；`mark != null` → 只在 `incoming != null` 且匹配时接受」。已核实这样改是安全的：`RewriteQueueDao.kt:34-39` 的 `updateStatus` 是裸 `UPDATE ... WHERE word_id = :wordId`，对不存在的行是 no-op，不会抛、也不会凭空造一条队列行（恢复完该词显示为「无标记」，符合预期）。这一条不修，用户重装一次就永久丢掉所有重写记录。
3. **清测试数据**（必须和第 2 项**同一次**提交）：删掉 `tools/rewrite-service/data/` 下的 `queue.json` / `results/` / `logs/`，或给 `/rewrite/results` 加「只返回 `updatedAt` 晚于部署时间」的门槛。原因见重要-3：现在有 17 条无 clientId 的历史结果 + 7 条 `probe-*` 探针结果，恢复逻辑一修好它们就会立刻灌进用户手机。
4. **`git add tools/rewrite-service/`**（`data/` 已被 ignore），并顺手把 `docs/wb-gateway-repair-20261107/` 删掉或加进 `.gitignore`。

### P1（试用期间盯着，不拦装机）

5. **step5/grok**：从 `generate.txt` 物理删掉配图/例句段（省 token、大概率解决 step5 空 content），或给 step5 单列更大的 `max_tokens`。修不好就在 UI 下拉里把这两个标成「实验」，别让用户在 2 个坏模型上耗 10 分钟才发现失败。
6. **`finish_fail` 补 superseded 守卫**（`store.py:232-236` 抄 `:204-208` 的 3 行）。
7. **给 8765 加一层最薄的鉴权**（哪怕是启动时生成一个随机串写进设置页、手机端放进 header），或者至少把 `/rewrite/marks` 与 `/rewrite/results` 限制成只接受已被手机配置过的那台设备的请求。这条是红线级的，只是因为局域网私用所以排在 P1。

### 不建议的做法

- 不要把「5 个模型全部跑通」放宽成验收通过条件就算了——现在的问题不是「模型偶尔失败」，是 `step5` 在 12000 token 下**结构性**产出空 content（同一词 3 连败），`portdan-gpt` 2 条全部评审不过。放宽门槛等于把「生成质量」这个产品的核心价值也一起放宽了。
- 不要为了让 step5 通过而把评委的及格线从 8 降到 7（线上 `increase` 出现过 7 分被拒）。那会把上轮 B5 好不容易堵上的 fail-open 又打开。

---

## 6. 复评方法与证据边界

- 代码：`git status` 全量对过一遍（Android 11 改 5 新；`tools/cardback-editor/server.py` 改；`tools/rewrite-service/` untracked）。
- 线上数据：`data/queue.json` 按 model×state 统计（13 done / 11 failed）、`data/logs/service.log` 12:20–12:49 的完整一轮、`/rewrite/status`、`/models`、`/rewrite/results?since=0&limit=200`（逐条看 clientId，这才是发现重要-2/3 的证据）。
- 时序：靠文件 mtime 与日志时间戳对齐，判断某个失败发生在某次修复之前还是之后（`pipeline.py` 12:19:41 / `providers.py` 12:37:21 / 服务重启 12:37:29）。
- 未做：没有在模拟器上重跑一遍 UI 流程（独立测试报告已覆盖，且本轮只做只读复评）；没有调真实模型复现 grok 502（会花钱、且不可控）。
