# -*- coding: utf-8 -*-
"""润色样张：按 polish-spec-v2 只改 4 个表达字段，产出 before/after 对照。"""
import json, os, sys, re, time, glob, pathlib
sys.path.insert(0, r"D:\codex\MindForge_MF\english_production")
from word_pipeline import paths, relay

PROJ = pathlib.Path(r"D:\codex\MindForge_MF\english_production")
RUNS = PROJ / "word-runs"
OUT = pathlib.Path(r"D:\deepseek harness\workcodex\.tmp")
WORDS = ["bit", "time", "work", "abandon", "rope"]
FIELDS = ["hint", "scene", "exampleZh", "collocations"]

def newest_run(word):
    best = None
    for d in RUNS.glob(word + "-2026*"):
        f = d / "work" / "card.text.json"
        if f.exists() and (best is None or f.stat().st_mtime > best[1].stat().st_mtime):
            best = (d, f)
    return best

RULES = """你是英文单词记忆卡的中文文案编辑。只修改下面四个字段，其余一律不许输出。

## 只能输出这四个键
{"hint": ["...","..."], "scene": "...", "exampleZh": "...", "collocations": ["..."]}

## 字数硬上限（超一个字都算失败）
- hint：数组 2–5 条，每条 2–32 字
- scene：50–220 字
- exampleZh：4–200 字
- collocations：数组 2–4 条，每条 4–80 字

## 中文表达七条
A1 一句一动作：每段最多一个动词，「的」前修饰最多一层。hint/exampleZh 每个逗号段 ≤12 字；scene 因为要拼到 50 字以上，允许每个逗号段 ≤16 字。
A2 删可删的修饰：只保留大小/颜色/数量这类有信息量的。
A3 删「正确的废话」：换掉这个词句子照样成立的话，就删。
A4 hint 必须给卡面上没有的信息：复述单词或释义 = 废。一条 hint 要把一个画面元素和词义绑在一起，遮住英文要能反推。
A5 翻译腔黑名单：进行+名词、使得、对于…而言、作为…、通过…来、在…情况下、非遭受义的被、同句「他的/她的」出现 2 次以上。
A6 搭配格式固定为「英文搭配 + 空格 + 中文意思」，**英文部分一个字都不许删**，中文 ≤8 字、不用「表示/意为/指」。例："a bit of 一点儿"。只输出中文而丢掉英文 = 不合格。
A7 scene 只写画面里已经有的东西，不许新增画面（新增 = 图文不符）。

## 风格
说人话，可以俏皮，但不要抢戏：读者要能记住词，不是记住笑话。不要用网络烂梗，不要地域、性别、身材、低俗玩笑。

## 输入（只读，不要改）
{input_json}
"""

def validate(pol):
    errs = []
    if set(pol.keys()) - set(FIELDS):
        errs.append("多余键: " + ",".join(sorted(set(pol.keys()) - set(FIELDS))))
    h = pol.get("hint")
    if not (isinstance(h, list) and 2 <= len(h) <= 5):
        errs.append("hint 条数不合法")
    else:
        for x in h:
            if not (isinstance(x, str) and 2 <= len(x) <= 32):
                errs.append(f"hint 条目长度 {len(x) if isinstance(x,str) else '?'} 越界")
    sc = pol.get("scene", "")
    if not (isinstance(sc, str) and 50 <= len(sc) <= 220):
        errs.append(f"scene 长度 {len(sc) if isinstance(sc,str) else '?'} 越界(50-220)")
    ez = pol.get("exampleZh", "")
    if not (isinstance(ez, str) and 4 <= len(ez) <= 200):
        errs.append(f"exampleZh 长度 {len(ez)} 越界(4-200)")
    co = pol.get("collocations")
    if not (isinstance(co, list) and 2 <= len(co) <= 4):
        errs.append("collocations 条数不合法")
    else:
        for x in co:
            if not (isinstance(x, str) and 4 <= len(x) <= 80):
                errs.append(f"collocations 条目长度 {len(x) if isinstance(x,str) else '?'} 越界")
    blob = json.dumps(pol, ensure_ascii=False)
    for w in ["进行", "使得", "对于", "作为", "通过", "情况下", "表示", "意为", "指"]:
        if w in blob:
            errs.append("翻译腔/解释腔词: " + w)
    longs = [seg for seg in re.split(r"[，。；！？、,;]", sc) if len(seg) > 12]
    if longs:
        errs.append(f"scene 有 {len(longs)} 个逗号段 >12 字")
    return errs

def main():
    cfg = dict(paths.load_config())
    relay.prepare(cfg)
    rows, t0 = [], time.time()
    for i, w in enumerate(WORDS, 1):
        hit = newest_run(w)
        if not hit:
            print(f"[{i}/{len(WORDS)}] {w}: 没有文本 run，跳过"); continue
        card = json.loads(hit[1].read_text(encoding="utf-8"))
        cur = {k: card.get(k) for k in FIELDS}
        prompt = RULES.replace("{input_json}", json.dumps(
            {"word": card["word"], "pos": card.get("pos"), "meaning": card.get("meaning"),
             "elements": card.get("elements"), "method": card.get("method"), **cur},
            ensure_ascii=False, indent=1))
        s = time.time()
        try:
            ans, route = relay.json_chat(cfg, prompt, purpose="polish-sample", max_tokens=1600)
        except Exception as e:
            print(f"[{i}/{len(WORDS)}] {w}: 调用失败 {type(e).__name__}: {e}"); continue
        pol = ans if isinstance(ans, dict) else {}
        errs = validate(pol)
        print(f"[{i}/{len(WORDS)}] {w}: {time.time()-s:.1f}s 字段={list(pol.keys())} 校验={'OK' if not errs else errs}")
        rows.append({"word": w, "run": hit[0].name, "before": cur, "after": pol, "errs": errs})

    md = [f"# 润色样张（{time.strftime('%Y-%m-%d %H:%M')}，共 {len(rows)} 词，耗时 {time.time()-t0:.0f}s）", ""]
    for r in rows:
        md.append(f"## {r['word']}  ({r['run']})")
        md.append("")
        for f in FIELDS:
            b, a = r["before"][f], r["after"].get(f)
            md.append(f"**{f}**")
            md.append(f"- 原（{len(json.dumps(b, ensure_ascii=False))-2 if b is not None else 0} 字符）：{json.dumps(b, ensure_ascii=False)}")
            md.append(f"- 新：{json.dumps(a, ensure_ascii=False)}")
            md.append("")
        if r["errs"]:
            md.append("> 校验问题：" + "；".join(r["errs"]))
            md.append("")
    out = OUT / f"polish-sample-{time.strftime('%Y%m%d-%H%M')}.md"
    out.write_text("\n".join(md), encoding="utf-8")
    (OUT / "polish-sample-latest.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    print("写出:", out)

if __name__ == "__main__":
    main()
