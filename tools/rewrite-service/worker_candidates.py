"""汇总按书反馈，补全词条并按锁、冷却和方法优先级选择候选。"""

from __future__ import annotations

import rewrite_protection as protection
import worker_target


def _feedback_rows(api, store):
    """流式读 feedback.jsonl，坏行跳过（这份日志是多进程 append 的）。"""
    return protection.feedback_rows(store.data_dir)


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
    return worker_target.select(api, store, config or api.load_config(),
                                runtime if runtime is not None else api._load_runtime(), limit)
