# -*- coding: utf-8 -*-
"""润色 v3：Claude 两份准则（中文表达 A / 可回忆性 B）+ grok 那份（G 规则、口吻分层、硬边界）合并后交 ds4.1-flash 执行。"""
import json, re, sys, time, pathlib
sys.path.insert(0, r"D:\codex\MindForge_MF\english_production")
from word_pipeline import paths, relay

PROJ = pathlib.Path(r"D:\codex\MindForge_MF\english_production")
RUNS = PROJ / "word-runs"
OUT = pathlib.Path(r"D:\deepseek harness\workcodex\.tmp")
WORDS = ["bit", "time", "work", "abandon", "rope"]
FIELDS = ["hint", "scene", "exampleZh", "collocations"]

STYLE = """冷幽默（程度/数量/时间/逻辑词，靠反差）
损友（社交、拒绝、评价类动词，当面损，不骂群体）
正经人跑偏（学术商务法律词，前半句端着后半句滑一下）
双关（易混音易混形，必须同时保住正确读音）
带体温（吃喝、身体感觉、日常物件，口语化，不玩梗）——默认口吻
梗王（词本身常被玩梗时才用，每批最多约一成卡）
毒舌（贬义指向行为不指向人群）
荤（可进 hint/exampleZh/搭配中文；scene 只有画面已画出成人身体距离时才可带一句，不许加动作）"""

PROMPT_HEAD = """你是英文单词记忆卡的中文文案编辑。只改四个字段：hint / scene / exampleZh / collocations。
其余 17 个字段（英文记忆法、动作、图片提示词等）全部锁死：不要输出、不要复述、不要提及。

【硬边界，碰线即作废】
1. 读音、拼写、词义必须与原卡一致。近形词只能当「别混」的对照，不许把近形词的读音安到本词上。
2. scene 只写配图里已经看得见的东西。不许新增物体、动作、伤口、体液、心理活动、画面外的人。
3. 禁止：血腥、残肢、暴力伤害、未成年、真人、地域或族群歧视。
4. 允许：冷梗、地狱笑话、成人性暗示、双关。前提是帮人记住这个词，且不改音、不改义。
5. 一张卡只用一个主口吻；荤或地狱笑话全卡最多一处；没把握就不用梗。

【字数，硬性】
- hint：2–5 条，每条 2–32 个汉字
- scene：50–220 个汉字
- exampleZh：4–200 个汉字
- collocations：2–4 条，每条 4–80 字符，英文搭配一个字都不许删改，格式 `英文 —— 中文`

【中文表达规则】
A1 一句一动作：hint 与 exampleZh 每个逗号段不超过 12 个字，一段最多一个动词，「的」前面的修饰最多一层。
A2 删掉可删的修饰：只保留大小、颜色、数量这类真信息。
A3 删掉正确的废话：换成别的词也成立的话，删。
A4 不许白给信息：每条 hint 必须给出卡面上看不出来的东西；只是复述单词或释义，等于废话。
A5 翻译腔黑名单：进行+名词、使得、对于…而言、作为、通过…来、在…情况下、非遭受义的「被」、同一句出现两个「他的/她的」。
A6 搭配只给意思不给解释：不要写「表示」「意为」「指」；中文要带口气，不干巴。
A7 写完复核一遍字数。

【记忆钩子规则】
B1 拆词要拆到能复述：读者闭上眼要能说出「怎么拆的」。
B2 谐音只许一跳：词 → 音 → 义，不许二段谐音。
B3 每条 hint 要过「三天后还能不能把人拉回来」。
B4 场景只写四层：谁、在哪、拿什么、比例或姿态。
B5 该词有两张图（A/B），是同一关系换侧面，不许写成两件事。
B6 原卡 hint 里的记忆思路（拆词、谐音、画面关系）是核心资产：hint 至少两条必须落在原有的拆词/谐音/画面上，不许换成纯语音注解或词典式补充。可以改措辞、可以加一条，但不能把抓手整个换掉。
B7 scene 里出现的人、物、动作，必须和原 scene 描述一致。原 scene 说人已经走开，就不许再写一个人站在旁边。

【口吻分层，只选一个主口吻】
%s
密度：梗最多一处，其余必须是抓手，不许每条都在抖包袱。

【自检，写完逐条打勾，必须全是「是」】
1 只含四个键？ 2 hint 2–5 条且每条 2–32 字？ 3 每条 hint 都扣住正确读音、拼写或本词义？
4 scene 50–220 字且没有画面外的东西？ 5 scene 的张力只来自已画出的比例、姿势或空间？
6 没有血腥、残肢、暴力伤害？ 7 没有未成年、真人、地域族群歧视？
8 exampleZh 仍是原英文例句，只是带了语气？ 9 每条搭配英文未改、中文不干不串义？
10 只有一个主口吻，且荤或地狱笑话不超过一处？

【输出】
只返回一个 JSON 对象，不要 Markdown、不要前言、不要自检文字。键顺序固定：
{"hint": ["…"], "scene": "…", "exampleZh": "…", "collocations": ["英文 —— 中文"]}
collocations 必须覆盖原卡全部英文条、顺序不变、英文与原字符串逐字相同。

## 输入（只读）
{input_json}
""" % STYLE


def newest_run(word):
    best = None
    for d in RUNS.glob(word + "-2026*"):
        f = d / "work" / "card.text.json"
        if f.exists() and (best is None or f.stat().st_mtime > best[1].stat().st_mtime):
            best = (d, f)
    return best


CN = re.compile(r"[\u4e00-\u9fff]")
BANNED = ["进行", "使得", "对于", "作为", "情况下", "表示", "意为",
          "残肢", "断肢", "断臂", "割开", "内脏", "喷血", "血流不止"]
# 正则型禁词：避免把「手指」「指着」这种正常用词误杀，只抓词典式释义腔。
BANNED_PAT = [re.compile(r"意指|特指|泛指|指代|指的是"),
              re.compile(r"通过[^，。；！？]{0,8}来")]


def eng_of(item):
    """取出「英文 —— 中文」里前导的英文部分；只比字母骨架，忽略数字与标点。"""
    head = re.split(r"—", item)[0]
    head = re.split(r"[一-鿿]", head)[0]
    head = head.replace("->", " ").replace("→", " ").replace("—", " ")
    letters = re.findall(r"[A-Za-z][A-Za-z'’.\-]*", head)
    return " ".join(letters).strip().lower()


def cn_len(s):
    return len(CN.findall(s))


def validate(pol, before):
    errs = []
    if set(pol.keys()) - set(FIELDS):
        errs.append("多余键: " + ",".join(sorted(set(pol.keys()) - set(FIELDS))))
    h = pol.get("hint")
    if not (isinstance(h, list) and 2 <= len(h) <= 5):
        errs.append("hint 条数")
    else:
        for x in h:
            if not isinstance(x, str):
                errs.append("hint 条目非字符串"); continue
            tail = bool(re.search(r"(——>|→)", x))
            lo = 0 if tail else 2
            if not (lo <= cn_len(x) <= 32):
                errs.append(f"hint 条目长度 {cn_len(x)} 越界")
                continue
            for seg in re.split(r"[，。；！？、,;]", x):
                if cn_len(seg) > 14:
                    errs.append(f"hint 段过长({cn_len(seg)}字): {seg}")
    sc = pol.get("scene", "")
    if not (isinstance(sc, str) and 50 <= cn_len(sc) <= 220):
        errs.append(f"scene 长度 {cn_len(sc) if isinstance(sc, str) else '?'} 越界(50-220)")
    ez = pol.get("exampleZh", "")
    if not (isinstance(ez, str) and 4 <= cn_len(ez) <= 200):
        errs.append(f"exampleZh 长度 {cn_len(ez) if isinstance(ez, str) else '?'} 越界")
    co = pol.get("collocations")
    if not (isinstance(co, list) or isinstance(co, dict)):
        errs.append("collocations 类型")
    else:
        items = co if isinstance(co, list) else [f"{k} —— {v}" for k, v in co.items()]
        if not (2 <= len(items) <= 4):
            errs.append("collocations 条数")
        ob = before.get("collocations") or []
        ob = ob if isinstance(ob, list) else [f"{k} —— {v}" for k, v in ob.items()]
        for i, x in enumerate(items):
            if not isinstance(x, str) or not (4 <= len(x) <= 80):
                errs.append("collocations 条目长度越界")
                continue
            if not re.search(r"[A-Za-z]", x):
                errs.append("collocations 丢了英文")
            if i < len(ob):
                eo, en = eng_of(str(ob[i])), eng_of(x)
                # 旧格式原卡可能带 en/zh 标签或箭头，先去标签再比；允许前缀/包含关系
                eo_c = re.sub(r"(en|zh)", " ", eo)
                eo_c = re.sub(r"\s+", " ", eo_c).strip()
                if not (eo.startswith(en) or en.startswith(eo)
                        or eo_c.startswith(en) or en.startswith(eo_c)
                        or (en and en in eo_c)):
                    errs.append(f"英文被改: {eo} -> {en}")
    blob = json.dumps(pol, ensure_ascii=False)
    own = str(before.get("meaning", "")) + str(before.get("exampleZh", ""))
    for w in BANNED:
        if w in blob and w not in own:
            errs.append("触词: " + w)
    for pat in BANNED_PAT:
        m = pat.search(blob)
        if m:
            errs.append("触词: " + m.group(0))
    return errs


def main():
    cfg = paths.load_config()
    relay.prepare(cfg)
    rows, t0 = [], time.time()
    for i, w in enumerate(WORDS, 1):
        hit = newest_run(w)
        if not hit:
            print(f"[{i}] {w}: 没有 run"); continue
        d, f = hit
        card = json.loads(f.read_text(encoding="utf-8"))
        cur = {k: card.get(k) for k in FIELDS}
        prompt = PROMPT_HEAD.replace("{input_json}", json.dumps(
            dict(word=card.get("word"), phonetic=card.get("phonetic"), pos=card.get("pos"),
                 meaning=card.get("meaning"), method=card.get("method"), split=card.get("split"),
                 derivation=card.get("derivation"), propEn=card.get("propEn"),
                 actionAEn=card.get("actionAEn"), actionBEn=card.get("actionBEn"),
                 example=card.get("example"), **cur), ensure_ascii=False, indent=1))
        s = time.time()
        try:
            ans, route = relay.json_chat(cfg, prompt, purpose="polish-v3", max_tokens=1900)
        except Exception as e:
            print(f"[{i}/{len(WORDS)}] {w}: 调用失败 {type(e).__name__}: {e}"); continue
        pol = ans if isinstance(ans, dict) else {}
        errs = validate(pol, cur)
        print(f"[{i}/{len(WORDS)}] {w}: {time.time()-s:.1f}s 校验={'OK' if not errs else errs}")
        rows.append({"word": w, "run": d.name, "before": cur, "after": pol, "errs": errs})

    md = [f"# 润色 v3 样张（{time.strftime('%Y-%m-%d %H:%M')}，{len(rows)} 词，ds4.1-flash，Claude+grok 准则合并）", ""]
    for r in rows:
        md.append(f"## {r['word']}")
        md.append("")
        for fld in FIELDS:
            md.append(f"**{fld}**")
            md.append(f"- 原：{json.dumps(r['before'][fld], ensure_ascii=False)}")
            md.append(f"- 新：{json.dumps(r['after'].get(fld), ensure_ascii=False)}")
            md.append("")
        if r["errs"]:
            md.append("> 校验：" + "；".join(r["errs"]))
            md.append("")
    p = OUT / "polish-v3-sample.md"
    p.write_text("\n".join(md), encoding="utf-8")
    (OUT / "polish-v3-sample.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    print("saved", p, f"{time.time()-t0:.0f}s")


if __name__ == "__main__":
    main()
