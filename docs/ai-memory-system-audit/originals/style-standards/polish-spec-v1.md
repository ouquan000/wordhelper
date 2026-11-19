# 带背卡「表达层」润色规范 v1

> 适用模型：deepseek-v4.1-flash（批量执行）
> 上游依据：Claude Opus 5.5（可回忆性标准，`.tmp\reply-B-opus.txt`）+ 中文表达标准（待 Gemini 4 Argon 补位）
> 制定原则：只动表达层，不动记忆设计；动手前先看「锁死清单」。

---

## 0. 一句话

把 `hint` / `scene` / `exampleZh` / `collocations` 四个字段改到「读一遍就能和画面挂上」，
其余 17 个字段一个字都不许碰。

---

## 1. 锁死清单（碰了 = 5183 张 A 图作废，26 小时重出）

| 字段 | 为什么锁死 |
|---|---|
| `elements` / `method` / `split` / `derivation` | 记忆思路本身，改了等于换一张卡 |
| `propEn` / `subjectAnchorEn` / `styleAnchorEn` | 直接进 `imagePromptA`，图已按它生成 |
| `actionAEn` / `stateAEn` / `actionBEn` / `stateBEn` | 同上，且是 A/B 两个机位的唯一区别 |
| `imagePrompt` / `imagePromptA` / `imagePromptB` | 图已生成，改字 = 图文不符 |
| `word` / `phonetic` / `pos` / `meaning` | 词头，改了就不是这个词 |

**只允许写这四个键**（输出 JSON 里出现别的键 = 判不合格，整条丢弃）：

```json
{"hint": ["...", "..."], "scene": "...", "exampleZh": "...", "collocations": ["...", "..."]}
```

---

## 2. 字数硬上限（超一个字都算失败，程序会退）

| 字段 | 结构 | 上限 |
|---|---|---|
| `hint` | 数组 2–5 条 | **每条 2–32 字符** |
| `scene` | 字符串 | **50–220 字符** |
| `exampleZh` | 字符串 | **4–200 字符** |
| `collocations` | 数组 2–4 条 | **每条 4–80 字符** |

注：中文按字符数算，标点也算。

---

## 3. 五条可判定规则（来自 Opus，已转成打勾项）

### R1 拆词粒度
- 规则：拆到能单独挂上意义或读音的最小块；没有词根的短词整体记，不按字母拆。
- 判：每个拆出的元素都被某条 `hint` 用到，而且不只是复述这个字母怎么读。
- 正：teacher = teach（教）+ er（人）
- 反：b 读 /b/、i 发短音、t 轻收 —— 每块都没错，拼起来不指向词义。
- ⚠️ 本规范不改 `split`/`elements`，R1 只用于**判断 hint 有没有把拆出来的块用上**。

### R2 谐音/字形牵强线
- 规则：只许一跳：读音或字形 → 中文词 → 词义。
- 判（三项全过）：
  1. 谐音词首音节的声母、韵母与原词一致；
  2. 中文词直接通到词义，中间不再借助别的事物；
  3. 不涉及性、排泄、身体隐私、歧视。
- 正：ambition 谐「俺必胜」→ 雄心
- 反：ponder 谐「胖的」→ 胖子爱发呆 → 沉思（两跳）

### R3 hint 能不能回想
- 规则：一条 hint 要把**一个画面元素**和**词义**绑在一起。
- 判（五项全勾才算过）：
  - □ 有英文词
  - □ 有中文义
  - □ 有一个**在画面里指得出来的名词**
  - □ 有比较词或因果词（比…/因为…/所以…）
  - □ 遮住英文，能从中文反推出这个词
- 一票否决：只讲读音、只讲拼写、「别和 X 混」这类，算看完等于没看。
- 正：石板比她高一倍，giant = 巨大
- 反：lump 读 /lʌmp/，短促有力，尾音轻
- ⚠️ 这里的「画面元素」必须是 `propEn`/`subjectAnchorEn` 里出现过的东西，不许引入新道具（否则和已生成的图对不上）。

### R4 场景层级
- 规则：只写四层——人物的 1 个识别特征、道具、动作、承载词义的对比。
- 判：
  1. 对比必须有参照物（写「比她肩宽」，不写「很大」）；
  2. 删掉某句后，hint 用到的画面元素都还在 → 说明这句是多余的。
- 光照、季节、机位、表情如果不承载词义，删掉。
- 正：绳子在她腰上绕三圈，尾端拖地
- 反：黄昏街角暖光斜照，她神情安静，手握一段绳子

### R5 A/B 换角度（本规范不改 action 句，只用来校验 scene 描述是否贴合）
- 规则：B 改的是人和道具的空间关系，道具本身不改。
- 判：① 两句动词不同；② 人和道具的距离、高低、接触方式至少变一项；③ 变化让词义对比更明显；④ 两句都逐字包含道具短语。
- 正：A holds a length of rope up / B kneels on a length of rope
- 反：B holds a length of rope, smiling（只多了表情）

---

## 4. 禁词表（出现即退回）

`生动` `有画面感` `自然` `流畅` `增强记忆` `印象深刻` `有助于` `让人` `帮助记忆`
`更好的` `画面感` `直观` `加深印象` `便于` `使得` `让你` `帮你记住`

理由：这些词无法判定、也无法在画面里指出来。

---

## 5. 改坏判定表

| 现象 | 级别 | 处置 |
|---|---|---|
| 引入新道具（`propEn` 里没有的东西） | 红 | 退回 |
| 出现禁词 | 红 | 退回 |
| 超字数 | 红 | 退回 |
| 输出里带锁死字段 | 红 | 丢弃整条 |
| `hint` 条数 <2 或 >5 | 红 | 退回 |
| 谐音两跳以上 | 红 | 退回 |
| 只讲读音/拼写 | 黄 | 重写该条 |
| 场景里出现光照/天气等不承载词义的描写 | 黄 | 删该句 |
| 三条 hint 说的是同一件事 | 黄 | 合并重写 |
| 比原卡更短但信息量持平 | 绿 | 接受 |

---

## 6. 自检断言（程序跑，不靠模型自觉）

```python
assert set(out) <= {"hint","scene","exampleZh","collocations"}
assert 2 <= len(out["hint"]) <= 5
assert all(2 <= len(h) <= 32 for h in out["hint"])
assert 50 <= len(out["scene"]) <= 220
assert 4 <= len(out["exampleZh"]) <= 200
assert 2 <= len(out["collocations"]) <= 4
assert all(4 <= len(c) <= 80 for c in out["collocations"])
assert not any(w in json.dumps(out, ensure_ascii=False) for w in BANNED)
# 道具白名单：hint/scene 里出现的中文名词必须能映射回 propEn/subjectAnchorEn
```

---

## 7. 五张对照样张（before → after，风格锚点）

**① 谐音类 · ambition**
- before: `hint: ["读作 /æmˈbɪʃn/，重音在第二音节","联想：俺必胜"]`
- after: `hint: ["谐音「俺必胜」，她握拳的手压在胸口","拳压胸口那一刻，她想要的那个位置就是 ambition：雄心"]`

**② 拆词类 · teacher**
- before: `hint: ["teach 教 + er 人"]`
- after: `hint: ["teach 教 + er 人，合起来就是教书的那个人","她把粉笔头按在黑板边，转身面向二十张脸"]`

**③ 道具对比类 · giant**
- before: `scene: "一个很高的人站在石板旁边"`
- after: `scene: "她仰头看石板，石板比她高出一倍，影子盖住她整张脸"`

**④ 易错警示类 · affect**
- before: `hint: ["注意和 effect 区分，affect 是动词"]`
- after: `hint: ["石板被绳子拽了一下，位置变了：affect 是去动别人","名词是 effect，那是被动的结果，不是她这个动作"]`

**⑤ 例句层 · exampleZh**
- before: `exampleZh: "这次事故影响了他的一生。"`
- after: `exampleZh: "绳子一拽，石板歪了——那一下就是 affect：她改变了石板的处境。"`

---

## 8. 批量执行流程（下一步）

1. `kimi-k3.1-pro` 打分：对 5198 条按 R2/R3/R4 三项打 0–3 分，只捞**总分最低的 10%（约 520 条）**。
2. `deepseek-v4.1-flash` 按本规范重写这 520 条的表达层四字段。
3. 程序跑 §6 断言 + §4 禁词 + 道具白名单。
4. 人工抽 20 条看风格是否跑偏（对照 §7）。
5. 通过后写回 `card.text.json` 的副本，不覆盖原文件（先落 `card.text.polished.json`），等用户确认再替换。
