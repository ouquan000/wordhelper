# -*- coding: utf-8 -*-
"""润色批处理：多路并发跑 polish_v3 的准则，产物落 <run>/work/card.text.polished.json，永不覆盖原卡。

用法：
  python polish_batch.py --lanes 3 --limit 20 --dry-run
  python polish_batch.py --lanes 5 --limit 40 --tag pilot
  python polish_batch.py --lanes 5 --until 22:30
"""
import argparse, json, queue, re, sys, threading, time, pathlib, traceback
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, r"D:\deepseek harness\workcodex\.tmp")
sys.path.insert(0, r"D:\codex\MindForge_MF\english_production")

import polish_v3 as P
from word_pipeline import paths, relay

OUT = pathlib.Path(r"D:\deepseek harness\workcodex\.tmp")
LOG = OUT / "polish-batch.jsonl"

# 两派准则的侧重不同：G 派（grok）偏口吻与梗，A 派（Claude）偏表达与可回忆性。
BIAS_G = """
【本批口吻倾向：G 派（口吻优先）】
在满足全部硬边界的前提下，允许并鼓励：冷幽默、损友式调侃、双关、地狱笑话（一处）、成人暗示（一处，且只在画面已画出成人身体距离时）。
hint 要像人写的、带语气；但抓手不许丢，梗不许抢戏。
"""
BIAS_A = """
【本批口吻倾向：A 派（表达与可回忆性优先）】
口吻克制：以清清楚楚、一句一动作、三天后还能拉回来为第一优先。
梗最多一处，拿不准就不用。宁可干净，不许油。
"""


def school_of(card):
    """按词语义粗分两派，目标比例 G(claude 侧重梗/grok 派) 4 : A 6。用稳定哈希保证可复现。"""
    import zlib
    w = (card.get("word") or "").lower()
    method = (card.get("method") or "").lower()
    h = zlib.crc32(w.encode("utf-8"))
    if method in ("root", "polysemy") or len(w) >= 9:
        return "A"
    if method in ("sound", "meaning") and len(w) <= 7 and h % 3 == 0:
        return "G"
    return "G" if h % 5 < 2 else "A"


def build_prompt(card, school):
    cur = {k: card.get(k) for k in P.FIELDS}
    payload = json.dumps(dict(
        word=card.get("word"), phonetic=card.get("phonetic"), pos=card.get("pos"),
        meaning=card.get("meaning"), method=card.get("method"), split=card.get("split"),
        derivation=card.get("derivation"), propEn=card.get("propEn"),
        actionAEn=card.get("actionAEn"), actionBEn=card.get("actionBEn"),
        example=card.get("example"), **cur), ensure_ascii=False, indent=1)
    head = P.PROMPT_HEAD.replace("{input_json}", payload)
    bias = BIAS_G if school == "G" else BIAS_A
    return head.replace("## 输入（只读）", bias + "\n## 输入（只读）")


def polish_one(cfg, word, rounds=2):
    hit = P.newest_run(word)
    if not hit:
        return {"word": word, "status": "no_run"}
    d, f = hit
    done_file = d / "work" / "card.text.polished.json"
    if done_file.exists():
        try:
            prev = json.loads(done_file.read_text(encoding="utf-8"))
            if prev.get("hint"):
                return {"word": word, "run": d.name, "status": "skip"}
        except Exception:
            pass
    card = json.loads(f.read_text(encoding="utf-8"))
    cur = {k: card.get(k) for k in P.FIELDS}
    school = school_of(card)
    prompt = build_prompt(card, school)
    last_errs, t0 = [], time.time()
    for attempt in range(1, rounds + 1):
        try:
            ans, route = relay.json_chat(cfg, prompt, purpose=f"polish-{school}", max_tokens=1900)
        except Exception as e:
            msg = f"{type(e).__name__}: {e}"
            if ("429" in msg or "6004" in msg or "quota" in msg.lower()) and attempt < rounds:
                time.sleep(20)
                last_errs = [msg[:120]]
                continue
            return {"word": word, "run": d.name, "status": "call_error",
                    "err": msg[:200], "secs": round(time.time() - t0, 1)}
        pol = ans if isinstance(ans, dict) else {}
        errs = P.validate(pol, cur)
        if not errs:
            out = d / "work" / "card.text.polished.json"
            out.write_text(json.dumps({
                **pol,
                "_meta": {"word": card.get("word"), "school": school, "model": cfg.get("model"),
                          "attempt": attempt, "polishedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
                          "spec": "claude-A7+B7 + grok-G16", "srcRun": d.name},
            }, ensure_ascii=False, indent=1), encoding="utf-8")
            return {"word": word, "run": d.name, "school": school, "status": "ok",
                    "secs": round(time.time() - t0, 1), "attempt": attempt,
                    "before": cur, "after": pol}
        last_errs = errs
        if attempt < rounds:
            prompt = prompt + "\n\n## 上一轮被打回的原因（逐条修掉，其余不许动）\n" + \
                     "\n".join("- " + e for e in errs)
    return {"word": word, "run": d.name, "school": school, "status": "invalid",
            "errs": last_errs, "secs": round(time.time() - t0, 1),
            "before": cur, "after": pol}


def word_universe(limit=0):
    words = []
    for d in sorted(P.RUNS.glob("*-2026*")):
        f = d / "work" / "card.text.json"
        if not f.exists():
            continue
        w = d.name.split("-2026")[0]
        if w and w not in words:
            words.append(w)
    words.sort()
    return words[:limit] if limit else words


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lanes", type=int, default=3)
    ap.add_argument("--limit", type=int, default=20)
    ap.add_argument("--rounds", type=int, default=2)
    ap.add_argument("--tag", default="")
    ap.add_argument("--words", default="", help="逗号分隔，只跑这些词")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    if a.words:
        words = [w.strip() for w in a.words.split(",") if w.strip()]
    else:
        words = word_universe(a.limit)

    if a.dry_run:
        print(f"候选 {len(words)} 词，lanes={a.lanes}，rounds={a.rounds}")
        print("前 10:", words[:10])
        return

    cfg = paths.load_config()
    relay.prepare(cfg)
    print(f"[start] {len(words)} 词 lanes={a.lanes} model={cfg.get('model')} tag={a.tag}", flush=True)

    done, ok, bad, t0 = 0, 0, 0, time.time()
    rows = []
    lock = threading.Lock()
    consec_fail = 0

    def work(w):
        return polish_one(cfg, w, a.rounds)

    with ThreadPoolExecutor(max_workers=a.lanes) as ex:
        futs = {ex.submit(work, w): w for w in words}
        for fu in as_completed(futs):
            try:
                r = fu.result()
            except Exception:
                r = {"word": futs[fu], "status": "crash", "err": traceback.format_exc()[-300:]}
            with lock:
                done += 1
                if r["status"] == "ok":
                    ok += 1
                    consec_fail = 0
                elif r["status"] == "skip":
                    pass
                else:
                    bad += 1
                    consec_fail += 1
                rows.append(r)
                line = f"[{done}/{len(words)}] {r['word']}: {r['status']} {r.get('secs','')}s"
                if r["status"] != "ok":
                    line += " | " + str(r.get("err") or r.get("errs"))[:160]
                el = time.time() - t0
                rate = done / el * 60 if el > 5 else 0
                print(f"{line} | {rate:.1f} 词/分", flush=True)
                with open(LOG, "a", encoding="utf-8") as fh:
                    fh.write(json.dumps(r, ensure_ascii=False) + "\n")
                if consec_fail >= 8:
                    print("[stop] 连续 8 词失败，停手等人", flush=True)
                    for f2 in futs:
                        f2.cancel()
                    break

    el = time.time() - t0
    print(f"[done] 成功 {ok} / 失败 {bad} / 共 {done}，{el:.0f}s，{ok/max(el,1)*60:.1f} 词/分", flush=True)
    stamp = time.strftime("%Y%m%d-%H%M")
    tail = f"-{a.tag}" if a.tag else ""
    (OUT / f"polish-batch-{stamp}{tail}.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    print("saved", OUT / f"polish-batch-{stamp}{tail}.json")


if __name__ == "__main__":
    main()
