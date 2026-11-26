"""Select CET6 targets using the synced word keys and the local 04:00 learning day."""
from datetime import datetime, time, timedelta

import rewrite_protection as protection

BOOK = "六级记忆卡"


def learning_window(now):
    local = datetime.fromisoformat(now).astimezone()
    day = (local - timedelta(hours=4)).date()
    end = datetime.combine(day + timedelta(days=2), time(4)).astimezone()
    return day.isoformat(), int(end.timestamp() * 1000)


def daily(runtime, day):
    value = runtime.get("daily") or {}
    if value.get("day") == day:
        return value
    return {"day": day, "attempts": 0, "fallback": 0}


def select(api, store, config, runtime, limit):
    day, end = learning_window(api._now())
    usage = daily(runtime, day)
    available = min(limit, config["max_per_day"] - usage["attempts"])
    if available <= 0:
        return []
    snapshot = api.store_mod._read_json(config["sync_path"])
    if not isinstance(snapshot, dict) or not isinstance(snapshot.get("rows"), dict):
        return []
    learned = {}
    for row in snapshot["rows"].values():
        if isinstance(row, dict) and row.get("t") == "progress" and row.get("b") == BOOK:
            learned[str(row.get("w") or "").strip().casefold()] = row
    upcoming = snapshot.get("upcoming_new_word_ids") or config.get("upcoming_new_word_ids") or []
    if not isinstance(upcoming, list):
        return []
    positions = {str(value).strip().casefold(): index for index, value in enumerate(upcoming)}
    votes = protection.latest_votes(api._feedback_rows(store))
    locked = protection.locked_words(store.locks(), votes)
    # Populate the same seed index used by the public lookup facade.
    api._word_source_lookup("")
    pool = []
    for word, entry in api._word_index["by_word"].items():
        vote = votes.get(word)
        stamp, liked = vote[0] if vote else (0, False)
        if liked or word in locked or entry.get("locked") is True:
            continue
        feedback = vote[1] if vote else {}
        word_id = entry.get("wordId") or entry.get("id") or feedback.get("wordId") or word
        book_id = feedback.get("bookId") or 0
        if str(word_id).casefold() in locked or store.is_locked(word_id, book_id):
            continue
        key = api.store_mod.lock_key(word_id, book_id)
        record = runtime.get("done", {}).get(key) or {}
        if record.get("engine") == protection.ENGINE and record.get("ok") and protection.timestamp(record.get("at")) >= stamp:
            continue
        if record.get("qualityAttempts", 0) >= 5 and protection.timestamp(record.get("at")) >= stamp:
            continue
        if (record.get("day") == day and protection.timestamp(record.get("at")) >= stamp):
            continue
        versions = store.results.get(entry["word"], {}).get("entries", [])
        if any(e.get("engine") == protection.ENGINE and e.get("status") == "ok"
               and not e.get("unverified") and protection.timestamp(e.get("updatedAt")) >= stamp
               for e in versions):
            continue
        progress = learned.get(word)
        position = positions.get(str(word_id).casefold(), positions.get(word))
        if position is not None and progress is None:
            rank = (0, position)
        elif not positions and progress is None:
            rank = (1, 0)
        elif progress is not None:
            due = progress.get("nrt")
            if type(due) is not int or due <= 0 or due >= end:
                continue
            lapses = progress.get("fsrs_lapses")
            tier = 0 if vote else 1 if isinstance(lapses, (int, float)) and lapses > 0 else 2
            rank = (2, tier, due)
        else:
            continue
        bucket = dict(key=key, word=entry["word"], word_id=word_id, book_id=book_id, book=BOOK,
                      phonetic=entry.get("phonetic"), meaning=entry.get("meaning"), pos=entry.get("pos"),
                      approach=feedback.get("approach") or entry.get("approach") or [],
                      instruction=api._row_complaint(feedback), method=feedback.get("method") or "",
                      model="ds-v41-flash", fallback=rank[0] == 1)
        pool.append((rank, bucket))
    pool.sort(key=lambda pair: pair[0])
    picked = []
    fallback_left = config["new_word_fallback_limit"] - usage["fallback"]
    for _, bucket in pool:
        if bucket["fallback"]:
            if fallback_left <= 0:
                continue
            fallback_left -= 1
        picked.append(bucket)
        if len(picked) == available:
            break
    return picked
