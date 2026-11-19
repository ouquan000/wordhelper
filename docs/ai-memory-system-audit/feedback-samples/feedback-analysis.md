# 英文记忆卡重写服务 · 用户反馈数据审计报告

- 审计类型：**只读**（源文件未修改、未移动、未删除；仅在本目录写入本报告与样本文件）
- 审计对象：`tools/rewrite-service/data/` 下 feedback.jsonl、queue.json、results/*.json（62 个）、logs/service.log
- 快照时间：2026-11-19 17:54（+08:00）
- 文件指纹：feedback.jsonl 111,432 B / mtime 2026-11-19 17:09:39；queue.json 193,010 B / mtime 2026-11-19 17:54:09（**服务仍在运行，queue 仍在增长**）；service.log 116,848 B / mtime 2026-11-19 17:54:09
- 编码：均为 UTF-8（读取时按 `utf-8-sig` 兼容 BOM）；feedback.jsonl 为 JSONL（每行一条，无空行）

---

## 0. 结论速览

| 问题 | 结论 |
|---|---|
| 总反馈条数 | **136** 条；其中 **2 条测试探针**，真实用户 **134** 条 |
| 赞 / 踩 | 全量 up 47 / down 89；**真实 up 45 / down 89（踩占 66.4%）** |
| 涉及单词 | 真实反馈 **86 个不同单词**；59 个词至少被踩 1 次，14 个词被踩 ≥2 次 |
| 带 instruction（用户原话）的反馈 | feedback.jsonl **0 条（字段存在但全为空串）**；真正原话在 **queue.json 的 37 条**（34 条真实设备 + 3 条测试） |
| 敏感信息 | **0 命中**（API Key / token / cookie / 密码 / 设备号 / 手机号 / 内网 IP / `C:\Users\<名>` / 身份证 / 邮箱 / URL 全部为 0）；无个人身份信息 |
| results 失败 | 123 条 entry 中 **3 条 failed（2.4%）**，失败原因集中在「链路只靠部分步骤、其余为装饰」「字形锚点覆盖不全」 |
| judge 打分 | 8 个维度共 723 个分值，**总平均 9.17 / 10** |

一句话：**踩的主要不是"写得不好"，而是"方法不对路"** —— 12 / 34 条用户原话明确排斥词根词缀法，8 条质问"我怎么想到这一截"，且 method=polysemy 100% 被踩、sound 0% 被踩。

---

## 1. 数据结构与字段字典

### 1.1 `feedback.jsonl`（137 行文本 / 136 条记录 / 111 KB）

一行一条 JSON，扁平结构，无嵌套。字段出现次数（缺字段=该行未写，不是 null）：

| 字段 | 出现次数 | 类型 | 含义 | 样例（已脱敏） |
|---|---|---|---|---|
| `clientId` | 136 | string | 客户端幂等键。App 端格式 `f{wordId}-{bookId}-{rating}-r{approachRev}`（见 `RewriteRepository.kt:1094` `feedbackClientId()`），同一条思路重复点踩靠 `-r{rev}` 区分 | `f73-1-down-r3` |
| `rating` | 136 | string | **投票本身，只有 `up` / `down` 两个值**（👍=up，👎=down），无中间态、无评分 | `down` |
| `receivedAt` | 136 | string | **服务端**收到时刻，ISO8601 带 `+08:00` | `2026-11-19T17:09:39+08:00` |
| `wordId` | 135 | int | 词条内部 id（1 行缺失：`authfix-test-1223`） | `73` |
| `bookId` | 135 | int | 词书 id：book 1 = 133 条、book 2 = 2 条、缺 1 条 | `1` |
| `word` | 134 | string | 单词原文（小写），2 条测试行无此字段 | `cathedral` |
| `approach` | 134 | string | **当时被投票的那份记忆思路全文**（多行纯文本，含①②③步骤） | `①cat 猫…` |
| `approachRev` | 134 | int | 该词思路的第几版（0 起）。分布：0=108、1=11、2=5、3=2、4=3、5=1、7=2、8=1、14=1 | `3` |
| `method` | 134 | string | 该思路使用的方法标签：root 49、meaning 41、contrast 16、shape 11、sound 9、polysemy 6、空 4（含 2 条探针） | `root` |
| `model` | 134 | string | 生成模型，**134 条全部 `ds-v41-flash`**（2 条探针无） | `ds-v41-flash` |
| `instruction` | 134 | string | 用户当时下的重写要求。**136 条全部为空串**（见 2.5 结构性原因） | `""` |
| `createdAt` | 134 | string | 客户端生成时刻，**格式不统一**：多数带 `+08:00`，1 条为 `2026-11-09T04:17:28Z`（UTC） | `2026-11-19T17:09:39+08:00` |

时间跨度：`receivedAt` 2026-11-09 12:21:42 → 2026-11-19 17:09:39（11 天）。**但按天分布极不均**：11-09 55 条、11-10 28 条、11-19 51 条，中间 11-11 ~ 11-18 **整整 8 天零反馈**。这更像"两批测试/两次集中试用"，而非稳定日常流量 —— 解读"踩率 66%"时须带上这个前提。

### 1.2 `queue.json`（193 KB）

顶层只有 3 个键：`updatedAt`（string）、`items`（array，**158** 项）、`acceptedMarkIds`（array，158 项，与 items 等长，做过的标记去重表）。

`items[]` 字段（按用途分组）：

- **请求侧**：`clientId`、`word`、`bookId`、`book`、`phonetic`、`pos`、`meaning`、`instruction`（用户原话，核心）、`currentApproach`（array，当前已有思路，重写的基础）、`model`、`priority`、`markedAt`、`createdAt`
- **状态机**：`state`、`stage`、`attempts`、`failures`、`nextRunAt`、`lastReason`、`startedAt`、`updatedAt`、`error`、`superseded`、`note`、`unverified`
- **评审产物**：`bestApproach`、`bestScores`、`qualityRejected`、`resultSeq`、`generation`、`reason`

状态分布（158）：`done` 136、`pending` 16、`failed` 2、`retry_wait` 2、`running` 2。
其它：`bookId` 为 null 116 / 1 42；`attempts` 1=120、0=15、2=8、null=6、3=4、4=4、5=1；`model` ds-v41-flash 157、step5 1；不同单词 66 个。

**关键关系**：`queue.items[].instruction` 才是"用户当时到底说了什么"，`feedback.approach` 则是"用户看到并投票的那份文本"。两者词集只交集 55 个（feedback 87 含空串 vs queue 66），feedback 里另有 21 个词（ability / behavior / benefit / census / critical / crude / debris / digital / economic / education / effective / emotion / enhance / environment / epidemic / establish / etiquette / generate / government / lawsuit / negative / population / proofread / quest…）在 queue 里查不到 —— 这批是**早期版本（1.4.7.1 之前）**没有入队记录的投票，无法回填其 instruction。

### 1.3 `results/*.json`（62 个文件）

每个文件对应一个词：`{ word: string, entries: [ … ] }`。文件按词命名（`cathedral.json`、`excerpt.json`…），entries 是该词的历次生成记录（`seq` 递增，`rev` 为版本号）。共 **123 条 entry**：`status` ok 120、failed 3。ok 里 `attempts`=1 的 100 条、=2 的 20 条（即 16.7% 需要第二轮才过）。

`entries[]` 字段：`seq`、`word`、`rev`、`approach`（成品全文）、`prev`（上一版）、`updatedAt`、`status`、`model`、`clientId`、`scores`（judge 各维度分，object）、`attempts`、`method`、`unverified`、`reason`、`bookId`、`book`、`error`。**没有 `issues` 字段** —— 失败评语实际存放在 `error`（见 §5）。

### 1.4 `logs/service.log`（1,195 行 / 117 KB）

纯文本行日志（时间戳 + 级别 + 词 + 阶段 + 结果）。**全文检索无 `feedback` 相关记录**：服务不记录赞踩事件。因此投票无法与"当时那次生成"在日志层面对齐，只能用 `word` + `approachRev` + `createdAt` 近似对齐。日志尾部（17:49）显示 `appropriate` 质量未过、第 5 次重试。

---

## 2. 统计

### 2.1 总览与探针剔除

| 项 | 全量 | 探针（排除） | 真实 |
|---|---|---|---|
| 记录数 | 136 | 2 | **134** |
| up | 47 | 2 | **45** |
| down | 89 | 0 | **89** |
| 踩占比 | 65.4% | — | **66.4%** |
| 不同单词 | 86 | — | 86 |
| instruction 非空 | 0 | 0 | 0 |

**探针识别规则**（`source=probe`）：
1. `clientId` 以 `probe-` 开头 → 本数据集 **0 条**；
2. `clientId` 非任何 App 端形态（既不是 `f{wordId}-{bookId}-{rating}-r{rev}`，也不是 `w{wordId}-{epochms}` / `w{wordId}-b{bookId}-{uuid}`）→ 命中 2 条：`restart-test-20261109-1221`（up，无 word/createdAt）、`authfix-test-1223`（up，无 word/wordId/createdAt）。命名（restart / authfix）直指测试用途，且缺关键业务字段，判为测试写入。

> 注意：`f73-1-down-r3` 这类看似"流水号"的 id **不是**探针，它就是 App 点踩时生成的幂等键（`RewriteRepository.kt:1094`）。按形态误判会把 134 条真实投票全部丢掉。

### 2.2 按方法看赞踩（真实 134 条）

| method | up | down | 合计 | 踩占比 |
|---|---|---|---|---|
| root（词根词缀） | 14 | 35 | 49 | **71.4%** |
| meaning（含义联想） | 8 | 33 | 41 | **80.5%** |
| contrast（对比/易混） | 7 | 9 | 16 | 56.2% |
| shape（字形） | 6 | 5 | 11 | 45.5% |
| sound（谐音） | 9 | 0 | 9 | **0.0%** |
| polysemy（一词多义） | 0 | 6 | 6 | **100%** |
| （空） | 1 | 1 | 2 | 50.0% |

**这是整份数据里最强的信号**：method 与踩率的相关性远强于"写得好不好"。谐音 9 票全赞，一词多义 6 票全踩，词根词缀 + 含义联想承担了 68 / 89 次踩（76%）。

### 2.3 被踩最多的词 Top 10（真实）

| # | word | up | down | 合计 |
|---|---|---|---|---|
| 1 | cathedral | 0 | 9 | 9 |
| 2 | endeavor | 0 | 5 | 5 |
| 3 | obscure | 1 | 4 | 5 |
| 4 | cognitive | 2 | 3 | 5 |
| 5 | hemisphere | 1 | 3 | 4 |
| 6 | referee | 0 | 3 | 3 |
| 7 | stereotype | 0 | 3 | 3 |
| 8 | mental | 1 | 2 | 3 |
| 9 | subsidy | 1 | 2 | 3 |
| 10 | adolescent | 0 | 2 | 2 |

（并列第 10 的还有 consequence / technology / pyramid / monopoly 各 0 赞 2 踩。）

`cathedral` 一个词吃了 9 次踩、0 赞，`approachRev` 从 0 一直重写到多版仍被踩 —— 是最值得单独立项复盘的词。踩得多的词与 queue 里"同一用户重复下指令"的词高度重合（cathedral 2 条指令、obscure 3 条、chronic 2 条、referee 2 条、endeavor 2 条、technology 2 条），说明**踩是"改了几版还不满意"的累积**，不是一次性手滑。

### 2.4 instruction 覆盖情况

- `feedback.jsonl`：**0 / 136 有非空 instruction（0%）**。字段在 DTO 里存在（`FeedbackDto.instruction`），但历史数据里 App 没填。`history.py:88` 的原注释写明了原因：「历史 feedback.jsonl 的 instruction 多为空（1.4.7.1 之前 App 没传），… queue.json 的 instruction 是当时请求的原始输入，必须带上。」→ **这是版本结构性缺失，不是数据损坏**。
- `queue.json`：**37 / 158 有非空 instruction（23.4%）**，其中 **34 条** clientId 为真实设备形态（`w{wordId}-{epochms}` 或 `w{wordId}-b{bookId}-{uuid}`），**3 条**为测试写入。
- 因此"用户原话"的有效样本池是 **34 条**，全部列在 §3。

---

## 3. 用户原话（34 条真实反馈，逐字引用，未改写、未纠正错别字）

| # | word | instruction（原文） |
|---|---|---|
| 1 | chronic | 整段重写 我不想用词根词缀法 你换个方法让我背 最后再提一嘴词根词缀发就行。。。。。 |
| 2 | evaporate | use homophone sound-alike memory, method tag sound, not root |
| 3 | obscure | 你这个意向太抽象了 根本看不懂 |
| 4 | obscure | 我不知道ob是什么意思 我也不想知道ocure什么意思 你就不能换个其他方法吗 |
| 5 | referee | 我很喜欢你这版的记忆思路 但是我没记忆点 优化一下呗 |
| 6 | premium | 我不要词根词缀记忆法！ |
| 7 | premium | 这是人机写的吧 逻辑都不通的 换个更有逻辑的说法 |
| 8 | cognitive | 不要用词根词缀法。。。。。 |
| 9 | chronic | 不要用词根词缀法。。。。 |
| 10 | subtle | 我不喜欢这个带背方法 |
| 11 | technology | 不要词根词缀 |
| 12 | nurture | 跟nature的关系 |
| 13 | referee | 你就不能换个记忆方法吗 |
| 14 | hemisphere | 我怎么知道hemi是什么意思 |
| 15 | cathedral | 换个方法背 这个不好背 |
| 16 | endeavor | 换个其他方法带背 |
| 17 | trait | 一笔和少许这两个意思呢 |
| 18 | obscure | 那你用什么东西想到那块墨呢？这逻辑都不对啊。 |
| 19 | skeptical | 我怎么知道skept这个词搁哪里来的？ 换个方法呗。 |
| 20 | celebrity | 不是，为什么我要想象啊，你这个太怪了，换一个。 |
| 21 | mental | 我怎么知道词根词缀什么意思？你换一个方法记呗。 |
| 22 | obscure | 不是，你们怎么老是带我寄那个？词根词缀呀，你就不能用含义联想之类的其他方法吗？ |
| 23 | linguistic | 我怎么知道词根词缀什么意思？ 换个方法。 |
| 24 | cathedral | 根本看不懂。 |
| 25 | technology | 不要词根词缀 |
| 26 | tenant | 你就不能换一个吗？ |
| 27 | chronic | 我该怎么想到a cute呢 |
| 28 | consequence | 我不要词根词缀 |
| 29 | radical | 我怎么通过radi想到根 |
| 30 | contribute | 我不想要词根词缀 |
| 31 | monopoly | 我不要词根词缀记忆法 |
| 32 | foster | 再优化一下 不用联想其他单词 |
| 33 | endeavor | 我怎么想到effort |
| 34 | suicide | 我怎么知道sui是什么意思 |

**按关键词计数（34 条，一条可命中多类）**：提及「词根/词缀」**12** 条；表达「换一个/换个/其他方法」**12** 条；质问「怎么知道 / 怎么想到 / 怎么通过」**8** 条；「看不懂 / 逻辑不通 / 太抽象 / 太怪」**4** 条；正面语气（喜欢 / 优化一下）**3** 条。

**主题聚类**：
1. **方法排斥（12 条）**——不是嫌内容差，是嫌"路子"不对："我不要词根词缀记忆法！" / "不是，你们怎么老是带我寄那个？词根词缀呀，你就不能用含义联想之类的其他方法吗？"
2. **溯因断链（8 条）**——用户追问"你凭什么从这一截跳到这个词"："我怎么通过radi想到根" / "我怎么知道sui是什么意思" / "我该怎么想到a cute呢" / "我怎么想到effort" / "我怎么知道skept这个词搁哪里来的？"。这与 §5 失败评语里的"空挂""装饰性一步"是同一病灶。
3. **看不懂/逻辑不通（4 条）**——"你这个意向太抽象了 根本看不懂" / "这是人机写的吧 逻辑都不通的" / "那你用什么东西想到那块墨呢？这逻辑都不对啊。" / "根本看不懂。"
4. **要求保留个别元素（3 条）**——"我就不能用……最后再提一嘴词根词缀发就行" / "再优化一下 不用联想其他单词" / "跟nature的关系"（要求补上同源词对照）。
5. **正面／增量（3 条）**——"我很喜欢你这版的记忆思路 但是我没记忆点 优化一下呗"（唯一一条先肯定后提需求，说明"体系感"是被认可的，缺的是**记忆点**）。

**3 条测试来源（不计入 34 条，仅备注）**：`e2e-1471-quixotic`、`live-20261110-ephemeral` 两条内容为 GBK 乱码（原文为「谐音派…不用词根」类中文被按错误编码写入，形如 `��г�����룬���ôʸ���`），`live-20261110-ephemeral-cn` 为可读中文「别用词根词缀拆，换个方法；最后一行提一嘴词根就行。」—— 与 #1 同义，属同一次灰度验证。

> 存疑 1 条：#2 `evaporate` 的 instruction 是**英文指令体**（"use homophone sound-alike memory, method tag sound, not root"，还带 `method tag` 这种内部字段口吻），clientId 却是正常 `w392-…`。判断为**开发者/内部人员用真机自测**，保留在 34 条内但在解读语气分布时应剔除（若剔除：中文真实用户 33 条）。

---

## 4. 安全审查（关键）

### 4.1 方法

对 4 类共 65 个文件（feedback.jsonl、queue.json、service.log、results/*.json×62）做**全文 + 逐字段**双路扫描，模式覆盖 18 类：

| 类别 | 检测模式 |
|---|---|
| API Key / 凭据 | `sk-`、`AIza`、`Bearer `、`xox[baprs]-`、`api_key`、`apikey` |
| token / 会话 | `token`、`secret`、`cookie`、`password`、`passwd`、`authorization`、`sessionid`、`csrftoken`、`Set-Cookie` |
| 联系方式 | 邮箱正则、中国大陆手机号 `1[3-9]\d{9}` |
| 网络标识 | 内网 IP（`10.` / `192.168.` / `172.16-31.`）、任意 IPv4、`http(s)://` URL |
| 设备/账号标识 | `SN`/`IMEI`/`OAID`/`UDID`/`android_id`/`deviceId` |
| 本机路径 | `C:\Users\<名>`、任意 `X:\…` 绝对路径 |
| 身份 | 18 位身份证、人名/住址线索（人工逐条读 instruction） |

### 4.2 结果：**0 命中**

| 文件 | API Key/token/cookie/密码 | 手机号 | 内网 IP / URL | 绝对路径（含用户名） | 设备序列号 | 身份证/邮箱 |
|---|---|---|---|---|---|---|
| feedback.jsonl（136 行，逐字段） | 0 | 0 | 0 | 0 | 0 | 0 |
| queue.json（158 items，逐字段） | 0 | 0 | 0 | 0 | 0 | 0 |
| results/*.json（62 文件 / 123 entries） | 0 | 0 | 0 | 0 | 0 | 0 |
| logs/service.log（1,195 行） | 0 | 0 | 0 | 0 | 0 | 0 |

补充事实：
- service.log 里连 `C:\`、`Users`、`http`、`@`、`sk-`、`api_key` 这些**字面量都一次没出现**，日志只写词名/阶段/耗时/结果。
- "**手机**"二字共出现 5 处，全部是词义画面描述，不是号码：queue.json 2 处（`obscure`："你拿手机拍照时手指按住镜头…"；`celebrity`："大家就举起手机喊名字"）、results/celebrity.json 1 处、results/obscure.json 2 处。**判定为非敏感**。
- `instruction` 34 条逐条人工读过：**无姓名、无住址、无电话、无账号、无可定位到个人的信息**；内容全是学习诉求与对思路的抱怨。**因此本次匿名化中 `[已移除:隐私]` 占位符实际替换 0 处**（机制已就位，未触发）。

### 4.3 唯一需要留意的是"伪标识符"，不是凭据

| 项 | 说明 | 风险 | 处置 |
|---|---|---|---|
| `clientId`（feedback） | `f{wordId}-{bookId}-{rating}-r{rev}` 可反推"哪个词、哪本书、投了什么票" | 低（不含个人信息） | 样本文件中**不保留**该字段 |
| `clientId`（queue） | `w{wordId}-b{bookId}-{uuid}` 含 **UUID**，可把同一设备的多次请求串成一条时间线 | 中低（可做行为关联，但不指向自然人） | 样本不含 queue 数据；报告中引用原话时**不带 clientId** |
| `wordId` / `bookId` / `approachRev` | 内部编号 | 低 | 仅在报告中作统计维度，样本仅保留 `approach_rev` |
| `model` | 模型名 | 无 | 保留（业务需要） |

**结论：本数据集不含 API Key、token、cookie、密码、设备序列号、真实账号、手机号、内网 IP，也不含带用户名的绝对路径 —— 四类文件全部 0 命中。可直接对外，匿名化只需处理 §4.3 的伪标识符与（若有）instruction 中的隐私片段。**

---

## 5. `results/*.json`：失败与打分

### 5.1 失败 3 条（123 条 entry 的 2.4%）

| 文件 | seq | rev | model | attempts | clientId | updatedAt |
|---|---|---|---|---|---|---|
| excerpt.json | 6 | 1 | ds-v41-flash | null | w6-<epoch-ms> | 2026-11-07T21:40:54 |
| excerpt.json | 7 | 1 | ds-v41-flash | null | w6-<epoch-ms> | 2026-11-07T21:50:31 |
| technology.json | 67 | 1 | ds-v41-flash | null | w30-<epoch-ms> | 2026-11-09T16:09:28 |

**没有 `issues` 字段**，评语在 `error` 里，统一以「两轮都没过：」开头，其后用「；」分隔具体评语。3 条共切出 **13 段**，**13 段互不相同（每条频次都是 1）** —— 所以"出现频率最高的前 10 条原话"在本数据集里不存在重复项：**去重后即 13 条全集，下列全部列出（按主题归并排序，同主题内按原文）**。

**主题 A｜链路只靠少数步骤，其余是装饰/注水（4 段，最高频主题）**
1. `[excerpt#7]` ③ 的闭链实际只依赖 ex（向外）+ cerpt（采）两个元素，② 的 extract 对照信息未被 ③ 消化，对照句沦为装饰性一步
2. `[technology#67]` ②和④基本是画面修饰（咬线头、扫散件、g一抓y一收），既不回拼写也不回词义，其中gy的读音 /dʒi/ 被动作手势糊过去了
3. `[technology#67]` 整卡是同一条工匠细活链，口吻统一，但真正承担音义闭环的只有①和⑤两步，中间三步是氛围注水
4. `[excerpt#6]` ③的收尾链路本身成立（ex-向外 + 剪下窄条 → 从整篇取出小段），但靠的是①③的动作组合，②的视觉块对词义贡献弱，更多只服务拼写

**主题 B｜字形/拼写锚点覆盖不全（3 段）**
5. `[excerpt#6]` ②的形状描述只给了 c 的弯钩和 pt 两笔，e、r 两个字母没有任何画面线索，字形锚点覆盖不全
6. `[excerpt#6]` 词干块实为 cerpt 而推导写 cerp-，末尾 t 只靠「pt 两笔」顺带带出，元素命名与实际切分不一致
7. `[excerpt#7]` cerpt 作为需首次学习的新词干，其 '采' 义的首次习得方式（是靠谐音、画面还是直接记忆）在三行推导中没有任何线索支撑

**主题 C｜谐音/元素"空挂"，没解释为什么等于这个意思（2 段）**
8. `[technology#67]` ①把 /tek/ 挂到「技艺」是空挂：没给任何谐音或线索解释这一截为何等于技艺，目标词回指的核心一环直接缺失
9. `[technology#67]` ③里 no/脑 的谐音本来成立（/nɑː/ 近『脑』），却绕去说 n 是拼音声母，把最好用的线索弱化了

**主题 D｜易混词未优先对比（2 段）**
10. `[excerpt#6]` excerpt 与 except 是同一高频范围内的真实易混词对，按「有易混词优先对比」应优先处理这对混淆，成品完全没有涉及
11. `[excerpt#7]` excerpt 与 except 是同一考纲内拼写高度相近的真实高频易混词对，按「有易混词优先对比」的优先级应优先处理，成品对此只字未提

**主题 E｜断言式收束（1 段）+ 定位自相矛盾（1 段）**
12. `[technology#67]` ⑤的词义收束是断言式拼接『细→动脑→系统→技术』，中间没有从画面回到 technology 的明确一跳，读者得自己补桥
13. `[excerpt#7]` ② 用 extract 作对照属于近义类比而非易混对比，且 '-cerpt 表示采' 只是平行断言，学习者无从得知此义，与 ① 'cerpt 是新词干' 的定位口吻矛盾

> 这 13 段与 §3 用户原话高度同构：用户说"我怎么想到effort""那你用什么东西想到那块墨呢"，judge 说"空挂""装饰性一步""读者得自己补桥" —— **评审标准和用户痛点是对齐的**，失败不是评审误判。

### 5.2 judge 各维度平均分（ok 条目 120 条，满分 10）

| 维度 | 样本数 | 平均 | 最低 | 最高 |
|---|---|---|---|---|
| 谐音过关 | 120 | **9.86** | 7 | 10 |
| 拆分成立 | 120 | 9.32 | 6 | 10 |
| 文本充足 | 120 | 9.17 | 8 | 10 |
| 方法选对 | 120 | 9.11 | 8 | 10 |
| 链条闭合 | 10 | 9.00 | 8 | 10 |
| 逻辑连贯 | 110 | 8.80 | 7 | 10 |
| 像人说话 | 110 | 8.80 | 8 | 10 |
| 诉求落实 | 13 | 8.62 | 8 | 9 |
| **合计** | **723 个分值** | **9.174** | 6 | 10 |

**读法（重要）**：八个维度的样本数不是 120，说明**打分维度按 method 动态启用**（`链条闭合`只对 10 条评、`诉求落实`只对 13 条评），不能把八个数简单平均成"综合分"。当前口径下综合均值 9.17 意味着**判分整体偏宽松**：平均 9+ 却仍有 66% 的踩率，说明离线 judge 与真实用户满意度之间存在明显 gap，judge 分数不适合作上线门槛的唯一依据。

---

## 6. 匿名化处理说明（对 `feedback-samples-anon.jsonl`）

- 收录范围：`source=real` 的 **134 条全部收录**（真实条数 134 ∈ [20, 200] 区间，按规则"全收"，未做抽样）。
- 保留字段（8 个，严格按约定）：`word`、`vote`（`up`/`down`）、`instruction`、`approach`（当时那份思路全文，未删改正文）、`approach_rev`、`model`、`created_at`、`source`。
- **移除**：`clientId`（伪标识符，可反推词/书/票型）、`wordId`、`bookId`、`receivedAt`（服务端时刻，可与日志关联）、`method`（可由 approach 正文推断，但保留会与 word 组合出更强的指纹；如需按方法分析请用本报告 §2.2 的聚合结果）。
- **隐私占位**：对 `instruction` 与 `approach` 正文跑邮箱/手机号/URL/绝对路径/身份证正则，命中即以 `[已移除:隐私]` 替换 —— 实际命中 **0 处**，故文件内无占位符。`instruction` 因源数据为空，134 条均为 `""`（**这是真实情况，不是脱敏结果**）。
- `created_at` 原样保留（含 `Z` / `+08:00` 混合格式），未做时区归一，以免丢失原始信息。
- 校验：134 行 / 97,677 字节 / 无空 `approach` / up 45 + down 89 = 134。

---

## 7. 局限与未验证项（请勿越过这些边界下结论）

1. **样本不代表大盘**：反馈只在 3 天出现（11-09、11-10、11-19），中间 8 天空白；134 条更像少数重度用户的密集试用，不能外推为"全体用户 66% 不满意"。
2. **无账号体系**：`clientId` 只能区分"设备/请求"，无法确认 134 条背后是几个真人；同一人多次点踩会让"踩得最多的词"排名偏移。**本报告未做去重到人的统计**（数据不支持）。
3. **feedback 与 queue 不能一一对应**：87 vs 66 个词、只有 55 个交集；早期投票（1.4.7.1 前）没有 instruction 留存，§3 的 34 条原话与 §2 的 134 条投票是**两个不同子集**，不可相加、不可互相归因。
4. **日志不记投票**：`service.log` 无 feedback 事件，投票与"当时模型版本/重试次数"只能用 word+rev 近似对齐，未做严格 join。
5. **`_recon` 副本已核对**：`_recon\1.4.7.2\E2E\isolated\...\feedback.jsonl`（85 行 / 69,789 B / 创建 2026-11-11 04:05）是主文件**前 85 行的逐字节前缀副本**；主文件创建于 2026-11-09 12:21 且后续增长至 136 行 → 判定为「isolated 测试环境从生产复制快照」，**不是生产文件被 E2E 污染**。但两条 `restart-test` / `authfix-test` 早期测试行确实混在生产文件里（已按 §2.1 剔除）。
6. **queue.json 是活文件**（mtime 随时刷新，服务在跑）：§1.2 的 158 items / 状态分布是 2026-11-19 17:54 的快照，复跑会漂移。
7. **judge 维度样本数不等**（10 / 13 / 110 / 120），说明打分口径按方法动态变化；跨 method 比较平均分不严谨。
