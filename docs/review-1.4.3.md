# 1.4.3「记忆思路重写」独立评审

> 评审方式：只读代码与文档，未修改任何实现文件（本报告是唯一新增文件），未操作模拟器。
> 评审范围：`docs/spec-1.4.3.md`、`tools/rewrite-service/**`、`tools/cardback-editor/server.py` 的新增路由、Android 侧 13 个改动文件 + 5 个新增文件、`README.md`。
> 评审时间：2026-11-07

## 总分：**79.5 / 100**

| 分项 | 满分 | 得分 | 一句话 |
|---|---|---|---|
| 需求符合 | 40 | **32** | 主链路、5 模型、离线补发、种子不覆盖、撤销全部落地；但 §3.1 的 payload 契约只送了一半，§4.4「评分只看成品」被违反 |
| 正确健壮 | 25 | **16.5** | 并发与落盘做得扎实，四个真问题：队列裁剪剪反、QUEUED 无出路、结果按词而非按 clientId 落库、评分维度不校验 |
| 代码卫生 | 20 | **17** | 分层干净、复用既有 helper、几乎无重复代码；两处死代码，提示词靠运行期收口算半个技术债 |
| 安全 | 10 | **10** | 无硬编码真密钥，凭据运行期读、不落日志、不进 diff |
| 文件卫生 | 5 | **4** | 服务目录自带 `.gitignore` 且 data 已挡住；扣分点是另一个任务的 `docs/wb-gateway-repair-20261107/` 临时证据目录混进 docs |

---

## 一、逐条对照硬要求

| # | 用户硬要求（原话 / 规格） | 结论 | 依据 |
|---|---|---|---|
| 1 | 「我们不要存卡，我们只要学习思路」只重写卡背 MEMORY 段 | ✅ 符合 | `RewriteRepository.applyResult()` 只写 `tb_word_memory.approach/prev_approach/rev/source`；pipeline 输出形状由 `pipeline.py:OUTPUT_SHAPE` 收窄到 `{method,split,elements,derivation}`，`scene/hint/example/imagePrompt` 不落任何文件 |
| 2 | 不生图、不产出例句、**不回灌 anki** | ✅ 符合 | 无任何 anki 写入路径；`tools/rewrite-service` 只写 `data/results/<word>.json` |
| 3 | 「手机上只是个符号，标记传到电脑上调模型」 | ✅ 符合 | 手机端只有 OkHttp 打 `/rewrite/marks` 与 `/rewrite/results`，无任何模型 SDK / key |
| 4 | 「生成文本并且润色agent必须与评分agent是两个agent」 | ✅ 主体符合，⚠️ 有折扣 | `providers.py:67 JUDGE_CANDIDATES` + `judge_target()` 取「第一个 model != 生成 model」的候选，结构上**永远**不会自己评自己；但 `pipeline.py:156` 把 `方法/拆法` 一起喂给了评委，与 §4.4-3「只看成品三行，不给它前两步的过程」直接冲突（见问题 B2） |
| 5 | 5 个模型 + wb 忽略 system，prompt 放 user | ✅ 符合 | `providers.py:33 MODELS` 与 `RewriteModel.kt:DEFAULT` 的 5 个 id **逐字一致**（`ds-v41-flash`/`step5`/`glm53`/`portdan-gpt`/`portdan-grok`），reasoning max/high/high/high/high 与规格表一致；wb 分支把 system 合并进 user 单条 |
| 6 | 离线可标记 / 有网补发 / 下次打开自动生效 | ✅ 符合 | 标记先落 Room（`RewriteStatus.PENDING`）→ 立刻 `sync()`，失败保留 PENDING；冷启动 `KaoyanWordApp.onCreate` 与 `LearningScreen.LifecycleResumeEffect` 各触发一次 `syncRewrite()` |
| 7 | 允许回退上一版、不破坏复习进度 | ✅ 符合（跨设备有缝） | `undo()` 写回 `prevApproach` → 清 prev、`rev-1`、`rev==0` 时 `source` 归还 seed；`tb_progress` 一行未动 |
| 8 | 代码卫生 / 文件夹卫生 / 「能不装新包就不装新包」 | ⚠️ 基本符合 | 新增依赖 0：Android 只用既有 okhttp/gson/room/coroutines/lifecycle，Python 纯标准库；文件夹卫生见 D 节 |
| 9 | 种子 version+1 不覆盖已重写（验收 4） | ✅ 符合 | `WordRepository.upsertAll` 先取 `getUserSourcedWordIds()` 再 `return@mapNotNull null` 跳过 |
| 10 | 版本号 / DB 迁移 14→15 | ✅ 符合 | versionCode 5→6、versionName 1.4.3；`MIGRATION_14_15` 加 3 列 + 建表 + 建唯一索引（名字与 Room 约定一致） |

**最实质的偏离**：规格 §3.1 的 `POST /rewrite/marks` 契约要求四行词头 `word/phonetic/pos/meaning`，实现里 `phonetic/pos/meaning` 被硬编码成空串（`RewriteRepository.kt:260-262`），`markRewrite` 也只传 `word.id/word.word/modelId/instruction`。而 `Word` 实体本身就有 `phonetic`/`meaning`（`data/entity/Word.kt:6-7`），`WordRepository` 组装 `Word` 时也是全的——数据就在手边没递过去。后果是电脑端 `pipeline._brief()` 的四行词头里三行是空的，生成阶段不知道词义、评分阶段无法判「链条闭合 / 谐音过关」，等于把最容易出错的环节交给了模型的猜测。这是本次最值得先修的一条（清单 A1）。

---

## 二、正确性与健壮性

### 做得好的地方（不加分但值得记）

- **并发**：手机端 `RewriteRepository.syncMutex = Mutex()` 把整轮 sync 串行化，冷启动与回前台两个触发点不会互相踩；电脑端 `store.py` 单写者 + `RLock/Condition`，`startup()` 用锁幂等起 2 个线程，`CONCURRENCY=2` 与规格一致。
- **落盘原子性**：`store._write_json` 先写 `.tmp` 再 `os.replace`，断电不会留下半截 JSON。
- **崩溃恢复**：`store.py:99-101` 启动时把 `running` 一律退回 `pending`，进程被杀不会让词永远卡在 running。
- **幂等**：`clientId = "w<wordId>-<createdAt>"`，PENDING 重试时复用原 `createdAt`（`RewriteRepository.kt` enqueueAndSync），重复投递不会产生两条。
- **迁移**：`MIGRATION_14_15` 用既有 `ensureColumnExists`/`hasColumn`，不新造 helper，重复执行也安全。
- **冷启动韧性**：`KaoyanWordApp` 里 `runCatching { rewriteRepository.sync() }`，重写服务异常不会连累 App 启动（验收 7）；`server.py::_start_rewrite` 失败只打印「重写服务没起来（编辑器照常可用）」。顺序也正确：`ensurePresetBooks` 在 `sync()` **之前**。
- **线程**：所有 OkHttp 调用与 Room 读写都在 `withContext(Dispatchers.IO)` + `runCatching`，主线程无网络/DB。

### 缺陷

**B1｜【重要】`store._prune()` 裁反了：留最旧、丢最新**

`store.py:356-365`：

```python
for item in self.items:              # items 是追加序，最先加的在最前
    if item.get("state") in ("pending", "running"): keep.append(item)
    elif dropped < KEEP_FINISHED: keep.append(item); dropped += 1
```

注释写的是「队列只留最近这么多条已完成的」，实现保留的是**最早**的 `KEEP_FINISHED` 条已完成项，把**最后**追加的（也就是刚跑完的那条）丢掉。累积到阈值之后，面板「最近 30 条」看不到最新完成/失败项，`/rewrite/retry` 也找不到它（`retry()` 在 `self.items` 里搜不到就报「没有这个词的失败项」）。按每天十条算大约两个月触发，属于不会立刻炸但一定会炸的类型。修法：`self.items[-KEEP_FINISHED:]` 之类，或倒序遍历。

**B2｜【重要】评分 agent 拿到了生成过程，与 §4.4-3 冲突**

`pipeline.py:152-157`：

```python
user = "%s\n\n方法：%s\n拆法：%s\n成品三行：\n%s" % (..., draft["method"], draft["split"], ...)
```

规格原话是「独立调用，只看成品三行，不给它前两步的过程」。独立模型这一点守住了（不是同一个 model），但「裁判能看见选手的推导」会让「拆分成立 / 方法选对」两个维度变成对生成器的复述式认可，评分区分度下降。修法很简单：只喂 `_brief` + 成品三行。

**B3｜【重要】`QUEUED` 没有任何出路**

手机端 `RewriteBar` 的点击逻辑是 `QUEUED -> Unit`，而 `cancel()` 只删非 QUEUED 的行（`RewriteRepository` 里对 QUEUED 直接 return）。一旦投递成功但结果永远不来——最现实的场景是用户把「卡背结构地址」换到另一台电脑，或电脑端 `data/` 被清掉——这个词就永久停在「等待电脑处理」，按钮点不动、也不能取消、更不能重标。建议给 QUEUED 一个超时（例如 `updated_at` 超过 N 天回到 PENDING）或允许「取消并重新标记」（服务端按 clientId 幂等，重复投递是安全的）。

**B4｜【重要】结果按 word 落库，晚到的结果会踩掉更新的本地标记**

`applyResult()` 先 `wordDao.getWordIdByKey(word)`，再不看 clientId 直接 `queueDao.updateStatus(wordId, ...)`（`RewriteRepository.kt:216-220`、`:250`）。而 `tb_rewrite_queue` 对 `word_id` 是唯一索引，一个词只有一行。于是：结果 A 回来时，如果用户已经为同一个词新建了标记 B（PENDING，尚未上传成功），B 会被直接标成 DONE/FAILED——`getPending()` 从此不会再捞它，**用户新写的 instruction 被静默吞掉**；对失败结果则是把新标记误标成 FAILED。服务端 `finish_ok` 的 result 里带了 `word` 与 `rev`，手机端却没用 `rev` 做陈旧性判断。建议：只在 `dto.rev > existing.rev` 或队列行 status 不是 PENDING 时才落。

**B5｜【次要】评分维度不校验，单维度也能判过**

`pipeline.py:178 low = min(scores.values())`，`scores` 是评委给什么就装什么。如果模型只回了 `{"拆分成立": 9}`，`low=9 ≥ PASS_LINE` 直接过。judge.txt 里虽然写了「任何一项 < 8 就 pass=false」，但代码既没核对 5 个必需 key 齐全，也没读 judge 自己给的 `pass` 字段。建议断言必需维度齐全，缺一个就当作不过（或重试）。

**B6｜【次要】`RewriteBar` 的输入草稿没有按词隔离**

`RewriteBar.kt:57-58` 的 `expanded` / `instruction` 是裸 `remember{}`，而 `RewriteBar` 在 `LearningScreen` 里处于固定位置、跨词复用同一个 composition slot。后果：在 abandon 上展开并写了「第三句太牵强」，翻到下一个词，输入框仍展开且文字还在，随手一点就把 A 词的问题描述标给了 B 词。加 `remember(wordId)` 或 `key(wordId)` 即可。

**B7｜【次要】撤销不跨设备**

`undo()` 只改本地库，不通知电脑，也不记「这个词被撤销过」。换机 / 清数据后 `since=0` 全量拉取会把历史结果重新灌回来，撤销失效。规格写的是「方案要能恢复历史」（`since=0`），两条需求本身有张力；当前实现选了「恢复」，可以接受，但值得在 README 的「已知限制」里写明。

**B8｜【次要】手机不发 `currentApproach`**

`store._enqueue` 支持读 `raw.get("currentApproach")`，`finish_fail` 拿它做 `prev` 兜底，`pipeline._brief` 也能拿它告诉模型「上一版思路」——但手机的 `MarkDto` 里没有这个字段，这条链路目前是通的管道、空的内容。既然 `applyResult` 已经在查 `wordMemoryDao`，把当前 `approach` 顺手带上成本极低。

**B9｜【次要】`server.py` 的模块名太通用**

`import service as rewrite_service` 把一个叫 `service` 的模块塞进 `sys.path`（`_REWRITE_DIRS` 两个候选），打包成 exe 后 `BASE_DIR` 是 exe 目录，`rewrite-service/` 子目录缺失时只有运行期一句提示，没有版本/路径校验。改成 `sys.path.insert` + `importlib.util.spec_from_file_location("rewrite_service", path)` 更稳，也免除与将来任何同名模块撞车的风险。

**B10｜【说明，不计扣分】`tb_word_memory.updated_at`**

规格 §5.3 的 SQL 列了 `ADD COLUMN updated_at`，实现没有加——因为 13→14 建 `tb_word_memory` 时已经有这一列（`AppDatabase.kt:643` 起的 `MIGRATION_13_14`）。**实现是对的，规格文本老了**，顺手把 §5.3 那段改掉即可。

同样，`source` 列在 DB 里带 `DEFAULT 'seed'` 而实体没写 `@ColumnInfo(defaultValue = "seed")`；Room 对「实体未声明默认值」的列不比较 defaultValue，所以不会崩，但补上更踏实。

**B11｜【说明】`markOf()` / `UPLOADABLE` 是死代码**

`RewriteRepository.markOf()`（:72）无调用方；`RewriteStatus.UPLOADABLE`（`RewriteQueueItem.kt:53`）无调用方。其余 DAO 方法都有真实使用点。

---

## 三、代码卫生（重复 / 死代码 / 命名 / 日志 / 依赖）

**好的方面**

- 分层干净：`RewriteRepository`（网络 + 落库）/ `RewriteQueueDao`（SQL）/ `RewriteViewModel` 逻辑（`LearningViewModel`）/ `RewriteBar`（纯 UI）职责分明，没有把 HTTP 塞进 Composable。
- 复用到位：`baseOrigin()` 复用既有的「卡背结构地址」只取 origin，没有新增一项用户配置；迁移复用 `hasColumn`/`ensureColumnExists`；`WordMemory` 的 `source/rev/prevApproach` 三个字段各自语义清楚，`SOURCE_SEED/SOURCE_USER` 是常量不是魔法串。
- 注释写的是「为什么」而不是「是什么」（例如 `LearningScreen` 里 `contentKey` 那段、`store._prune` 的意图注释——虽然实现和注释不符，但至少意图是写下来的）。
- 无 `println`/`Log.d`/`TODO`/调试开关残留；Python 侧唯一的 `print` 是 `service.py:263` 的独立模式启动提示，合理。
- **零新增依赖**，符合「能不装新包就不装新包」。Python 只用 `http.server/json/threading/urllib/os`；Android 只用项目已有的栈。

**扣分点**

- 死代码两处（B11），虽然各只有几行。
- `prompts/generate.txt` 原样保留六脉神剑全文（含 `scene/hint/example/imagePrompt` 以及生图指令），靠 `pipeline.py:OUTPUT_SHAPE` 在 user 尾部收口输出形状。作者留了注释解释「不改那份逐字复制的原文」，这个理由成立（保持与 MindForge 源头可比对、换模型时不用重抄），但代价是每次生成都要多烧几百 token 去讲一件本服务不做的事，也让模型更容易吐出多余字段。**不算缺陷，算债务**：等链路稳定后应该裁一份 `generate-derivation.txt`，或者至少在 prompt 末尾加一句「本服务只做文字，任何画面相关内容一律略过」。
- 命名总体清楚；`service.py` 里的 `respond()`（对外入口）与 `_dispatch()` 分工略隐晦，`panel.html` 与 `server.py` 的穿透函数 `_rewrite_passthrough` 名字过长但意图明确，可接受。

---

## 四、安全

**结论：无问题，满分。**

- 全仓 `grep -E "sk-[A-Za-z0-9]{8,}"` 只命中 `providers.py` 里 `"Bearer " + key` 的拼接，没有任何字面量密钥。
- `WB_KEY = "sk-wb-local-8788"` 是规格 §4.2 明文规定的本地网关占位 key，注释里也写清了「不是秘密」。
- portdan 的 key 运行期从 `~/.dsh/.credentials.yaml` 用 `credential()` 现读，读不到直接抛错，**明确拒绝回落硬编码**（`providers.py` 里那句「绝不回落到硬编码」）。
- 日志面：`service.log()` 只记词、耗时、分数、错误文本；`store.py` 不落请求头；`complete()` 的异常信息里也没有 header。没有任何路径会把 key 写进 `data/` 或仓库。
- `tools/rewrite-service/.gitignore` + `data/.gitignore`（内容 `*`）已经把运行期数据整体挡在版本库外，`git check-ignore` 实测 `data/queue.json` 被忽略。

唯一可提的加固项（不计扣分）：`/rewrite/*` 挂在 0.0.0.0 上是产品需要（手机要连），没有任何鉴权——局域网内任何人都能往队列里塞词、读结果。当前使用场景（家里/宿舍单人）下可接受，但值得在 README 里写一句「仅限可信局域网」。

---

## 五、文件卫生

**布局：合理。** 新增文件都放在约定位置——

- 服务在 `tools/rewrite-service/`（与 `tools/cardback-editor/` 并列，规格 §4.1 就是这么画的图）；
- Android 新文件按既有分层走：`data/entity/`、`data/dao/`、`data/model/`、`data/repository/`、`ui/screen/`，没有往根目录或 `ui/` 平铺；
- 没有临时文件散落在仓库根。

**扣分点**

- `docs/wb-gateway-repair-20261107/`（12 个文件：`acceptance-spec.md`/`.lock`、`check-idle.mjs`、`migrate-emulator-address.mjs`、`restore-main.ps1`、`verify-live.mjs`、`emulator-address-evidence.json`、`idle-before-deploy-initial.json`、`live-get-evidence.json`、`recovery-evidence.json` 等）是**另一个任务**（wb 网关修复）的临时脚本与证据快照，未被任何 `.gitignore` 覆盖（`git check-ignore` 无命中），一旦 `git add .` 就会进仓库。仓库确实有 `docs/shots/`、`docs/verify/`、`docs/testdata/` 这类证据目录的先例，所以不算「乱放」，但它与 1.4.3 无关、且带 `.mjs`/`.ps1` 可执行脚本，建议移出 `docs/` 或让对应任务自己决定去留。
- `tools/rewrite-service/data/` 里留着 3 条 e2e 测试记录（`e2e-abandon`/`e2e-adapt`/`e2e-appropriate`）、`state.json`(seq=3)、`results/*.json`、`logs/service.log`，以及 `__pycache__/` 下 4 个 `.pyc`。**它们全部被 gitignore 挡住**，是「处理了但不彻底」——交付前清一次更像样。

---

## 六、问题清单（按严重度）

### 阻断（必须修完才能发）
无。

### 重要（建议发版前修）

| # | 位置 | 问题 | 影响 |
|---|---|---|---|
| A1 | `app/.../data/repository/RewriteRepository.kt:260-262` | `phonetic/pos/meaning` 硬编码空串（§3.1 契约只送了一半） | 生成缺词义、评分缺判据，整条链在最该有据可依处变成猜 |
| B1 | `tools/rewrite-service/store.py:356-365` | `_prune()` 保留最旧的已完成项、丢掉最新的 | 累计够量后新结果从面板与 retry 里消失 |
| B2 | `tools/rewrite-service/pipeline.py:152-157` | 评分 agent 拿到 `method`/`split` | 违反 §4.4-3，评分独立性打折 |
| B3 | `app/.../ui/screen/RewriteBar.kt` + `RewriteRepository.cancel` | `QUEUED` 无超时、无取消、无重标 | 换电脑/服务端丢数据后该词永久卡死 |
| B4 | `app/.../data/repository/RewriteRepository.kt:216-220,250` | 结果按 word 落库、不看 clientId/rev | 晚到结果会吞掉用户更新的标记 |

### 次要（可进下一版）

| # | 位置 | 问题 |
|---|---|---|
| B5 | `pipeline.py:178` | 评分维度不校验，单维度即可通过 |
| B6 | `RewriteBar.kt:57-58` | `remember` 未按词隔离，跨词残留输入 |
| B7 | `RewriteRepository.undo()` | 撤销不通知电脑，换机 `since=0` 会覆盖回来（建议写进「已知限制」） |
| B8 | `RewriteRepository.MarkDto` | 不发 `currentApproach`，服务端已支持的上下文处于空转 |
| B9 | `tools/cardback-editor/server.py` | `import service` 模块名过于通用 + sys.path 注入 |
| B10 | `AppDatabase.kt:665` / `docs/spec-1.4.3.md` §5.3 | `source` 默认值可加 `@ColumnInfo`；规格里的 `updated_at` 已过时（13→14 就有，实现无误） |
| B11 | `RewriteRepository.kt:72`、`RewriteQueueItem.kt:53` | 死代码：`markOf()`、`RewriteStatus.UPLOADABLE` |
| C1 | `prompts/generate.txt` | 保留生图相关全文，靠运行期 `OUTPUT_SHAPE` 收口（技术债，宜裁派生版） |
| C2 | `README.md` | 1.4.3 段落仍写「尚未开工」，且只列了 deepseek 一个模型 |
| C3 | `docs/wb-gateway-repair-20261107/` | 另一任务的临时脚本/证据目录未忽略，混在 `docs/` |
| C4 | `tools/rewrite-service/data/`、`__pycache__/` | e2e 测试残留与字节码，已被 gitignore 挡住但目录在仓库里 |

---

## 七、一句话总评

链路搭得干净、边界守得住（独立评分、种子不覆盖、复习进度零改动、零新增依赖），但**递进模型的词头缺了音标/词性/释义**这一条，让整条流水线在最该有据可依的地方变成了猜测；再把队列裁剪、QUEUED 死路、按词落库三处修掉，就是个可以放心发 1.4.3 的版本。
