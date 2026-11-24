"""汇总按书反馈，补全词条并按锁、冷却和方法优先级选择候选。"""

from __future__ import annotations


def _feedback_rows(api, store):
    """流式读 feedback.jsonl，坏行跳过（这份日志是多进程 append 的）。"""
    path = api.os.path.join(store.data_dir, "feedback.jsonl")
    rows = []
    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = api.json.loads(line)
                except ValueError:
                    continue
                if isinstance(row, dict):
                    rows.append(row)
    except OSError:
        return []
    return rows


def _row_count(api, row):
    count, error = api.store_mod.feedback_count(row.get("count"))
    return 0 if error else count


def _reasons_text(api, row):
    """feedback 行的 reasons（list / JSON 字符串）合成一行文本；缺失返回空串。"""
    raw = row.get("reasons")
    if isinstance(raw, list):
        return "；".join(str(x).strip() for x in raw if str(x).strip())
    if isinstance(raw, str):
        text = raw.strip()
        if not text:
            return ""
        try:
            decoded = api.json.loads(text)
        except ValueError:
            decoded = None
        if isinstance(decoded, list):
            return "；".join(str(x).strip() for x in decoded if str(x).strip())
        return text
    return ""


def _row_complaint(api, row):
    """一条反馈行的用户原话：reasons + comment + instruction 合成，喂进生成 pipeline。"""
    parts = []
    reasons = api._reasons_text(row)
    if reasons:
        parts.append(reasons)
    comment = str(row.get("comment") or "").strip()
    if comment:
        parts.append(comment)
    instruction = str(row.get("instruction") or "").strip()
    if instruction:
        parts.append(instruction)
    return "；".join(parts)


def _word_source_lookup(api, word):
    """best-effort 只读：从 cet6_cards.json 读音标/释义，补 feedback 行缺失字段。"""
    if not api._word_index["loaded"]:
        api._word_index["loaded"] = True
        by_word = {}
        for path in api._WORD_SOURCE_PATHS:
            raw = api.store_mod._read_json(path)
            words = (raw or {}).get("words")
            if isinstance(words, list):
                for entry in words:
                    if isinstance(entry, dict):
                        key = str(entry.get("word") or "").strip().lower()
                        if key and key not in by_word:
                            by_word[key] = entry
        api._word_index["by_word"] = by_word
    return api._word_index["by_word"].get(str(word or "").strip().lower())


def candidates(api, store, config=None, runtime=None, limit=20):
    """挑出「该洗」的词：差评够多、差评多于好评、没锁定、最近没洗过。

    返回按优先级排好的列表（meaning/polysemy 优先，然后按差评票数）。
    """
    config = config or api.load_config()
    runtime = runtime or api._load_runtime()
    minimum = config["min_downvotes"]
    cooldown = config["done_cooldown_sec"]
    methods = set(config["target_methods"])
    done = runtime.get("done") or {}

    buckets = {}
    for row in api._feedback_rows(store):
        word = str(row.get("word") or "").strip()
        if not word:
            continue
        word_id = api.store_mod._lock_part(row.get("wordId"))
        book_id, book = api.store_mod.book_scope(row)
        key = api.store_mod.lock_key(word_id or word.lower(), book_id)
        bucket = buckets.get(key)
        if bucket is None:
            bucket = buckets[key] = {
                "key": key,
                "word": word,
                "word_id": word_id,
                "book_id": book_id,
                "book": book,
                "downVotes": 0,
                "upVotes": 0,
                "method": "",
                "instruction": "",
                "phonetic": "",
                "pos": "",
                "meaning": "",
                "approach": [],
                "model": "",
                "lastAt": "",
            }
        rating = str(row.get("rating") or "")
        count = api._row_count(row)
        if rating == "down":
            bucket["downVotes"] += count
        elif rating == "up":
            bucket["upVotes"] += count
        else:
            continue
        stamp = str(row.get("receivedAt") or row.get("createdAt") or "")
        if stamp >= bucket["lastAt"]:
            bucket["lastAt"] = stamp
            method = api.profile.normalize_method(row.get("method"))
            if method:
                bucket["method"] = method
            instruction = api._row_complaint(row)
            if instruction:
                bucket["instruction"] = instruction
            for field in ("phonetic", "pos", "meaning", "model"):
                value = row.get(field)
                if isinstance(value, str) and value.strip():
                    bucket[field] = value.strip()
            fresh = [str(x) for x in (row.get("approach") or row.get("currentApproach") or []) if str(x).strip()]
            if fresh:
                bucket["approach"] = fresh[:5]

    # 词库兜底：feedback 行没带音标/释义的词，从 cet6_cards.json 补全再进 pipeline。
    for bucket in buckets.values():
        if bucket["phonetic"] and bucket["meaning"]:
            continue
        entry = api._word_source_lookup(bucket["word"])
        if not isinstance(entry, dict):
            continue
        if not bucket["phonetic"]:
            bucket["phonetic"] = str(entry.get("phonetic") or "").strip()
        if not bucket["meaning"]:
            bucket["meaning"] = str(entry.get("meaning") or "").strip()

    picked = []
    for key, bucket in buckets.items():
        if not bucket["word"]:
            continue
        if bucket["downVotes"] < minimum:
            continue
        if bucket["downVotes"] <= bucket["upVotes"]:
            continue    # 已被点赞救回来的词不再动它
        if store.is_locked(bucket["word_id"] or bucket["word"], bucket["book_id"]):
            continue
        history_row = done.get(key)
        if isinstance(history_row, dict):
            if history_row.get("ok"):
                if api._recent(history_row.get("at"), cooldown):
                    continue
            elif int(history_row.get("failures") or 0) >= api.MAX_FAILURES_PER_WORD:
                if api._recent(history_row.get("at"), cooldown):
                    continue
        picked.append(bucket)

    def sort_key(bucket):
        method = bucket.get("method") or "meaning"
        in_target = 0 if method in methods else 1
        try:
            order = api.METHOD_ORDER.index(method)
        except ValueError:
            order = len(api.METHOD_ORDER)
        return (in_target, order, -bucket["downVotes"], bucket["key"])

    picked.sort(key=sort_key)
    return picked[:limit]
