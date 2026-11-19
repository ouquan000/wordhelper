# -*- coding: utf-8 -*-
"""组装导出：把每张卡的「原卡 + 润色四字段 + 图片路径」合成一条最终记录，供用户给的新格式再组装。

产出：
  export/all.jsonl          每行一张卡（word / 全字段 / 润色后四字段 / 图路径 / run 名）
  export/index.json         汇总（数量、字段完整性、缺图列表）
  export/by-word/<word>.json 单卡
用法：python polish_export.py [--only-polished]
"""
import argparse, json, pathlib, sys, time

PROJ = pathlib.Path(r"D:\codex\MindForge_MF\english_production")
RUNS = PROJ / "word-runs"
OUTDIR = pathlib.Path(r"D:\deepseek harness\workcodex\.tmp\export")

POLISH_FIELDS = ["hint", "scene", "exampleZh", "collocations"]

HIST = pathlib.Path(r"D:\deepseek harness\workcodex\.tmp\img-a")


def _pick_a(run_dir, word):
    """A 图可能在本 run 里，也可能在 10 月的历史池里。"""
    for q in sorted((run_dir / "assets" / "viewa").glob("*.png")):
        return str(q)
    for q in sorted((HIST / word / "assets" / "viewa").glob("*.png")):
        return str(q)
    for q in sorted((HIST / word).glob("*.png")):
        return str(q)
    return ""



def newest_text_run(word):
    best = None
    for d in RUNS.glob(word + "-2026*"):
        f = d / "work" / "card.text.json"
        if f.exists() and (best is None or f.stat().st_mtime > best[1].stat().st_mtime):
            best = (d, f)
    return best


def newest_polished_run(word):
    """优先取「最新且有润色产物」的 run。"""
    cands = []
    for d in RUNS.glob(word + "-2026*"):
        p = d / "work" / "card.text.polished.json"
        if p.exists():
            cands.append((p.stat().st_mtime, d, p))
    if not cands:
        return None
    cands.sort(reverse=True)
    _, d, p = cands[0]
    return d, p


def build(word, only_polished):
    hit = newest_polished_run(word)
    if hit:
        d, p = hit
        pol = json.loads(p.read_text(encoding="utf-8"))
        meta = pol.pop("_meta", {}) or {}
        src_file = d / "work" / "card.text.json"
        src = json.loads(src_file.read_text(encoding="utf-8")) if src_file.exists() else {}
    else:
        if only_polished:
            return None
        t = newest_text_run(word)
        if not t:
            return None
        d = t[0]
        pol, meta = {}, {}
        src = json.loads(t[1].read_text(encoding="utf-8"))

    card = dict(src)
    card.update({k: pol[k] for k in POLISH_FIELDS if k in pol})
    card["_polish"] = {
        "polished": bool(pol),
        "school": meta.get("school"),
        "spec": meta.get("spec"),
        "polishedAt": meta.get("polishedAt"),
        "rescued": meta.get("rescued", False),
    }
    card["_art"] = {
        "run": d.name,
        "viewa": _pick_a(d, word),
        "viewb": str(next(iter(sorted((d / "assets" / "viewb").glob("*.png"))), "") or ""),
    }
    return card


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only-polished", action="store_true")
    a = ap.parse_args()

    OUTDIR.mkdir(parents=True, exist_ok=True)
    (OUTDIR / "by-word").mkdir(exist_ok=True)

    words = set()
    for d in RUNS.glob("*-2026*"):
        if (d / "work" / "card.text.json").exists():
            words.add(d.name.split("-2026")[0])

    rows, no_polish, no_img = [], [], []
    have_polish = 0
    for w in sorted(words):
        c = build(w, a.only_polished)
        if not c:
            continue
        if c["_polish"]["polished"]:
            have_polish += 1
        else:
            no_polish.append(w)
        if not c["_art"]["viewa"]:
            no_img.append(w)
        rows.append(c)
        (OUTDIR / "by-word" / (w + ".json")).write_text(
            json.dumps(c, ensure_ascii=False, indent=1), encoding="utf-8")

    with open(OUTDIR / "all.jsonl", "w", encoding="utf-8") as fh:
        for c in rows:
            fh.write(json.dumps(c, ensure_ascii=False) + "\n")

    idx = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "levels": {},
        "cards": len(rows),
        "withPolish": have_polish,
        "withoutPolish": len(no_polish),
        "withoutImageA": len(no_img),
        "withoutPolishList": no_polish[:200],
        "withoutImageAList": no_img[:200],
    }
    (OUTDIR / "index.json").write_text(json.dumps(idx, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"导出 {len(rows)} 张卡：润色 {have_polish}、未润色 {len(no_polish)}、缺 A 图 {len(no_img)}")
    print("目录:", OUTDIR)


if __name__ == "__main__":
    main()
