# -*- coding: utf-8 -*-
"""润色收尾审计：
1) 用最终版规则把全程被判不合格的条目重判，能救的直接落盘；
2) 全量复查已落盘成品是否仍合规（防早期规则漏放）；
3) 输出库存统计 + 给用户看的抽检样张（before/after 对照）。
用法：python polish_audit.py            # 只审计
      python polish_audit.py --apply    # 顺带把可救的写盘
"""
import argparse, json, pathlib, sys, time, collections, random

sys.path.insert(0, r"D:\codex\MindForge_MF\english_production")
sys.path.insert(0, r"D:\deepseek harness\workcodex\.tmp")
import importlib
import polish_v3 as P
importlib.reload(P)

RUNS = P.RUNS
OUT = pathlib.Path(r"D:\deepseek harness\workcodex\.tmp")
JSONL = OUT / "polish-batch.jsonl"
FIELDS = P.FIELDS


def load_rows():
    rows = []
    if not JSONL.exists():
        return rows
    for ln in JSONL.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            rows.append(json.loads(ln))
        except Exception:
            pass
    return rows


def rescue(rows, apply=False):
    """把历史上被判不合格、但按现行规则其实合格的样本落盘。"""
    best = {}
    for r in rows:
        if r.get("status") != "invalid" or not r.get("after"):
            continue
        if not P.validate(r["after"], r["before"]):
            best[r["word"]] = r           # 后出现的覆盖前面的
    saved = 0
    for w, r in best.items():
        out = RUNS / r["run"] / "work" / "card.text.polished.json"
        if out.exists():
            continue
        if not apply:
            continue
        pol = dict(r["after"])
        pol["_meta"] = {"word": w, "school": r.get("school"), "model": "deepseek-v4.1-flash",
                        "attempt": 0, "rescued": True,
                        "polishedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
                        "spec": "claude-A7+B7 + grok-G16", "srcRun": r["run"]}
        out.write_text(json.dumps(pol, ensure_ascii=False, indent=1), encoding="utf-8")
        saved += 1
    return len(best), saved


def audit_files():
    """已落盘成品全量复验 + 统计。"""
    files = sorted(RUNS.glob("*-2026*/work/card.text.polished.json"))
    bad = []
    lens = collections.Counter()
    schools = collections.Counter()
    total_hint = 0
    for f in files:
        try:
            pol = json.loads(f.read_text(encoding="utf-8"))
            src = json.loads((f.parent / "card.text.json").read_text(encoding="utf-8"))
        except Exception as e:
            bad.append((f.parent.parent.name, ["读取失败 " + str(e)[:40]]))
            continue
        meta = pol.pop("_meta", {}) if isinstance(pol.get("_meta"), dict) else {}
        schools[meta.get("school") or "?"] += 1
        errs = P.validate(pol, {k: src.get(k) for k in FIELDS})
        if errs:
            bad.append((f.parent.parent.name, errs))
        for h in (pol.get("hint") or []):
            total_hint += 1
        lens[len(pol.get("scene") or "")] += 1
    return files, bad, schools, total_hint, lens


def sample_md(files, n=20, seed=7):
    random.seed(seed)
    pick = random.sample(files, min(n, len(files)))
    md = [f"# 润色抽检样张（{time.strftime('%Y-%m-%d %H:%M')}，{len(pick)} 条）", ""]
    for f in pick:
        try:
            pol = json.loads(f.read_text(encoding="utf-8"))
            src = json.loads((f.parent / "card.text.json").read_text(encoding="utf-8"))
        except Exception:
            continue
        w = src.get("word")
        md.append(f"## {w}  ·  {src.get('phonetic','')}  {src.get('pos','')}  {src.get('meaning','')}")
        md.append("")
        for fld in ("hint", "scene", "exampleZh", "collocations"):
            md.append(f"- **原 {fld}**：{json.dumps(src.get(fld), ensure_ascii=False)}")
            md.append(f"- **新 {fld}**：{json.dumps(pol.get(fld), ensure_ascii=False)}")
        md.append("")
    p = OUT / "polish-audit-sample.md"
    p.write_text("\n".join(md), encoding="utf-8")
    return p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    a = ap.parse_args()
    rows = load_rows()
    n_salvage, saved = rescue(rows, a.apply)
    print(f"可救样本 {n_salvage} 条，实际落盘 {saved} 条")

    files, bad, schools, total_hint, lens = audit_files()
    print(f"成品总数 {len(files)}，复验不合格 {len(bad)} 条")
    print("派别分布:", dict(schools))
    if bad:
        for w, e in bad[:10]:
            print("   ", w, e[:2])
    p = sample_md(files)
    print("样张:", p)

    # 真正还缺的词
    have = {f.parent.parent.name.split("-2026")[0] for f in files}
    allw = set()
    for d in RUNS.glob("*-2026*"):
        if (d / "work" / "card.text.json").exists():
            allw.add(d.name.split("-2026")[0])
    missing = sorted(allw - have)
    (OUT / "polish-missing.json").write_text(json.dumps(missing, ensure_ascii=False), encoding="utf-8")
    print(f"还缺润色的词 {len(missing)} 个（已写 polish-missing.json）")


if __name__ == "__main__":
    main()
