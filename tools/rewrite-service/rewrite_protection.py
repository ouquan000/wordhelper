"""Word-level protection shared by the scheduler and the publisher."""
import json
from datetime import datetime, timezone
from pathlib import Path

ENGINE = "rewrite-v2"


def timestamp(value):
    try:
        moment = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return (moment if moment.tzinfo else moment.replace(tzinfo=timezone.utc)).timestamp()
    except (TypeError, ValueError, OSError, OverflowError):
        return 0


def feedback_rows(directory):
    try:
        with (Path(directory) / "feedback.jsonl").open(encoding="utf-8-sig") as stream:
            return [json.loads(line) for line in stream if line.strip()]
    except FileNotFoundError:
        return []


def latest_votes(rows):
    latest = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        word = str(row.get("word") or "").strip().casefold()
        rating = row.get("rating")
        count = row.get("count", 1)
        if not word or rating not in ("up", "down") or type(count) is not int or not 1 <= count <= 50:
            continue
        stamp = timestamp(row.get("createdAt")) or timestamp(row.get("receivedAt"))
        rank = (stamp, rating == "up")
        if word not in latest or rank >= latest[word][0]:
            latest[word] = (rank, row)
    return latest


def locked_words(locks, votes):
    by_id = {(str(row.get("wordId")), str(row.get("bookId"))): word
             for word, (_, row) in votes.items()}
    words = set()
    for row in locks.values():
        if row.get("locked") is True:
            word = row.get("word") or by_id.get((str(row.get("word_id")), str(row.get("book_id"))))
            words.add(str(word or row.get("word_id") or "").strip().casefold())
    return words


def may_publish(entry, vote):
    if vote is None:
        return True
    (stamp, liked), _ = vote
    if not stamp or entry.get("reused") or timestamp(entry.get("updatedAt")) <= stamp:
        return False
    return not liked or (entry.get("source") in (None, "", "manual") and not entry.get("reused")
                         and not str(entry.get("clientId") or "").startswith("worker:"))
