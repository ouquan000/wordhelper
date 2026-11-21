#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""词卡合并发布（卡背编辑器 1.5.0 的「词卡热更」后端）。

做三件事：
1. 读种子 `content/seed/cet6_cards.json` —— 书籍词表 + 音标 / 释义 / 例句 / 搭配，
   是**唯一权威**，本模块只读它，从不回写；
2. 扫 `tools/rewrite-service/data/results/*.json`，挑出**合格**的 AI 记忆思路
   （status == "ok" 且 unverified == false 且 book 是「六级记忆卡」），同一个词多条
   合格记录取 rev 最大、其次 updatedAt 最新的那条；
3. 把合格记录的 approach / method 合并进种子词条，原子写到
   `published/cards/cet6_cards.json` + `published/cards/manifest.json`
   （version 自增、sha256 是发布文件字节的摘要，App 据此校验完整性）。

设计红线（为什么这么写）：
- 词表以种子为准：results 里种子没有的词**不新增**，避免书籍词表随 AI 记录漂移；
- 只替换 approach / method，音标 / 释义 / 例句 / 搭配永远来自种子；
- 写盘一律 .tmp + os.replace，覆盖前把旧文件复制成 .bak：断电或写一半不会留下半截
  文件，真出事还能拿 .bak 兜底；
- 单个 result 文件损坏只跳过它，不能让整次发布失败；
- 只用标准库；路径全部相对本文件定位，不依赖当前工作目录（打包成 exe 也能用）。

直接跑本文件等价于发布一次：`python card_publisher.py`。
"""

from __future__ import annotations

import glob
import hashlib
import json
import os
import shutil
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
# tools/cardback-editor -> 仓库根；种子和 results 都在它下面
PROJECT_ROOT = os.path.dirname(os.path.dirname(HERE))
SEED_PATH = os.path.join(PROJECT_ROOT, "content", "seed", "cet6_cards.json")
RESULTS_DIR = os.path.join(PROJECT_ROOT, "tools", "rewrite-service", "data", "results")
DEFAULT_PUBLISH_DIR = os.path.join(HERE, "published", "cards")

BOOK_NAME = "六级记忆卡"
CARDS_FILENAME = "cet6_cards.json"
MANIFEST_FILENAME = "manifest.json"
# 种子工作区是 CRLF，发布包跟着走（Android 侧按字节比 sha256，换行别来回跳）
NEWLINE = "\r\n"


# --------------------------------------------------------------------------- #
# 读 / 写
# --------------------------------------------------------------------------- #

def _read_json(path):
    """读 JSON。用 encoding="utf-8"（不是依赖 locale），Windows 上才不会踩 GBK。"""
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def _read_bytes(path):
    with open(path, "rb") as fh:
        return fh.read()


def _json_bytes(payload):
    """统一序列化：UTF-8 / ensure_ascii=False / 2 空格缩进 / CRLF / 末尾换行。"""
    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    if NEWLINE != "\n":
        # json.dumps 已经把字符串里的换行转义成 \\n，剩下的只有结构换行，替换是安全的
        text = text.replace("\n", NEWLINE)
    return text.encode("utf-8")


def _atomic_write(path, data):
    """先写 .tmp，再把旧文件复制成 .bak，最后 os.replace 顶上去。

    任何一步抛异常，原文件都还在（最差剩一个 .tmp，读侧只认正式文件名，不受影响）。
    """
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "wb") as fh:
        fh.write(data)
        fh.flush()
        os.fsync(fh.fileno())
    if os.path.exists(path):
        shutil.copy2(path, path + ".bak")
    os.replace(tmp, path)


def _now_iso():
    """本地时区 ISO8601，带 +08:00 之类的偏移（不是 naive 时间）。"""
    return datetime.now().astimezone().isoformat(timespec="seconds")


# --------------------------------------------------------------------------- #
# 从 results 里挑合格记录
# --------------------------------------------------------------------------- #

def _rev_value(entry):
    """rev 排序用整数；缺失或不是数字就当 0（最旧）。"""
    value = entry.get("rev")
    if isinstance(value, bool):
        return 0
    if isinstance(value, int):
        return value
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return 0


def _updated_at_value(entry):
    """updatedAt 排序键：能解析就换成 epoch 秒，解析不了当最旧（0.0）。

    不用字符串直接比，是因为不同偏移（+08:00 / +00:00）按字典序排会排错。
    """
    raw = entry.get("updatedAt")
    if not isinstance(raw, str) or not raw.strip():
        return 0.0
    text = raw.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return 0.0
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    try:
        return parsed.timestamp()
    except (OSError, OverflowError, ValueError):
        return 0.0


def _book_matches(entry):
    """book 缺失（没字段 / None）算「不限定」，存在就必须是本书；别的书直接丢。"""
    book = entry.get("book")
    if book is None:
        return True
    if not isinstance(book, str):
        return False
    return book.strip() == BOOK_NAME


def _qualifying_entry(entry):
    """合格判定，逐条对齐需求：status == ok、unverified == false、book 对得上。"""
    if not isinstance(entry, dict):
        return False
    if entry.get("status") != "ok":
        return False
    # 用 `is not False`：缺 unverified 字段也按「未验证」处理，宁可少并不可错并
    if entry.get("unverified") is not False:
        return False
    return _book_matches(entry)


def _entry_approach(entry):
    """只接受非空 list[str]；空思路不许覆盖种子里已有的内容。"""
    approach = entry.get("approach")
    if not isinstance(approach, list) or not approach:
        return None
    for item in approach:
        if not isinstance(item, str) or not item.strip():
            return None
    return [item.strip() for item in approach]


def _entry_method(entry):
    method = entry.get("method")
    if isinstance(method, str) and method.strip():
        return method
    return None


def _rank(record):
    """先 rev 大，再 updatedAt 新，最后用来源文件名兜底（同一批数据每次跑结果一致）。"""
    return (record["rev"], _updated_at_value(record), record["source"])


def collect_results(results_dir=None):
    """扫 results 目录，返回 (winners, stats)。

    winners: {word: {"approach": [...], "method": str | None, "rev": int,
                     "updatedAt": str, "source": 文件名}}
    stats:   {"files", "broken_files", "entries", "accepted", "rejected"}
    """
    directory = results_dir or RESULTS_DIR
    stats = {"files": 0, "broken_files": 0, "entries": 0, "accepted": 0, "rejected": 0}
    winners = {}

    for path in sorted(glob.glob(os.path.join(directory, "*.json"))):
        stats["files"] += 1
        try:
            payload = _read_json(path)
        except (OSError, ValueError, UnicodeDecodeError):
            # 单个 result 写一半 / 手工改坏：跳过它，不能让整次发布失败
            stats["broken_files"] += 1
            continue
        entries = payload.get("entries") if isinstance(payload, dict) else None
        if not isinstance(entries, list):
            stats["broken_files"] += 1
            continue

        for entry in entries:
            stats["entries"] += 1
            if not _qualifying_entry(entry):
                stats["rejected"] += 1
                continue

            word = entry.get("word")
            if not isinstance(word, str) or not word.strip():
                word = payload.get("word")
            if not isinstance(word, str) or not word.strip():
                stats["rejected"] += 1
                continue
            word = word.strip()

            approach = _entry_approach(entry)
            if approach is None:
                stats["rejected"] += 1
                continue

            updated_at = entry.get("updatedAt")
            record = {
                "approach": approach,
                "method": _entry_method(entry),
                "rev": _rev_value(entry),
                "updatedAt": updated_at if isinstance(updated_at, str) else "",
                "source": os.path.basename(path),
            }
            stats["accepted"] += 1
            current = winners.get(word)
            if current is None or _rank(record) > _rank(current):
                winners[word] = record

    return winners, stats


# --------------------------------------------------------------------------- #
# 合并
# --------------------------------------------------------------------------- #

def merge_into_seed(seed, winners):
    """把 winners 的 approach / method 写进种子词条，返回真正合并的词数。

    - 种子词顺序、其它字段（含字段顺序）都不动；method 紧跟 approach 插入，便于人读；
    - 种子没有的词不新增 —— 词表以种子为准，避免书籍词表漂移。
    """
    words = seed.get("words")
    if not isinstance(words, list):
        raise ValueError("种子文件结构不对：缺少 words 数组")

    merged = 0
    for entry in words:
        if not isinstance(entry, dict):
            continue
        word = entry.get("word")
        if not isinstance(word, str):
            continue
        winner = winners.get(word.strip())
        if winner is None:
            continue

        old_method = entry.get("method")
        method = winner["method"] or (old_method if isinstance(old_method, str) else None)

        rebuilt = {}
        for key, value in entry.items():
            if key == "method":
                continue  # 统一在 approach 后面重插，保证字段顺序稳定
            rebuilt[key] = value
            if key == "approach":
                rebuilt["approach"] = list(winner["approach"])
                if method:
                    rebuilt["method"] = method
        if "approach" not in rebuilt:
            # 兜底：种子理论上每条都有 approach，真缺也补上并保持可用
            rebuilt["approach"] = list(winner["approach"])
            if method:
                rebuilt["method"] = method

        entry.clear()
        entry.update(rebuilt)
        merged += 1

    return merged


# --------------------------------------------------------------------------- #
# 发布 / 保证已发布
# --------------------------------------------------------------------------- #

def _next_version(publish_dir):
    """上次 manifest.version + 1；manifest 缺失 / 损坏 / 字段不对都从 1 开始。"""
    try:
        payload = _read_json(os.path.join(publish_dir, MANIFEST_FILENAME))
    except (OSError, ValueError, UnicodeDecodeError):
        return 1
    version = payload.get("version") if isinstance(payload, dict) else None
    if isinstance(version, bool) or not isinstance(version, int) or version < 0:
        return 1
    return version + 1


def publish(base_dir=None):
    """合并 + 原子发布，返回 manifest（额外带 merged / stats，manifest 文件本身只有 5 个字段）。

    base_dir 是**输出目录**，默认 `<本文件>/published/cards`；种子和 results 的位置由
    模块常量 SEED_PATH / RESULTS_DIR 决定（测试可临时改这两个常量指向假数据）。
    """
    publish_dir = base_dir or DEFAULT_PUBLISH_DIR

    seed = _read_json(SEED_PATH)
    if not isinstance(seed, dict):
        raise ValueError("种子文件结构不对：顶层不是对象")

    winners, stats = collect_results()
    merged = merge_into_seed(seed, winners)
    # 词表没变，count 跟着实际条数走，防种子被手改后 count 与实际不一致
    seed["count"] = len(seed["words"])

    cards_bytes = _json_bytes(seed)
    manifest = {
        "version": _next_version(publish_dir),
        "book": BOOK_NAME,
        "count": len(seed["words"]),
        "sha256": hashlib.sha256(cards_bytes).hexdigest(),
        "updatedAt": _now_iso(),
    }

    os.makedirs(publish_dir, exist_ok=True)
    _atomic_write(os.path.join(publish_dir, CARDS_FILENAME), cards_bytes)
    _atomic_write(os.path.join(publish_dir, MANIFEST_FILENAME), _json_bytes(manifest))

    result = dict(manifest)
    result["merged"] = merged
    result["stats"] = stats
    return result


def ensure_published(base_dir=None):
    """保证发布包在：没发布过就 publish 初始化；有 cards 缺 manifest 就只补 manifest。

    启动路径不能因为缺文件就崩，也不能在发布包已就绪时白白重写一份 —— 重写会换
    sha256，已经热更过的 App 又得下一遍。
    """
    publish_dir = base_dir or DEFAULT_PUBLISH_DIR
    cards_path = os.path.join(publish_dir, CARDS_FILENAME)
    manifest_path = os.path.join(publish_dir, MANIFEST_FILENAME)

    if not os.path.exists(cards_path):
        return publish(publish_dir)

    try:
        manifest = _read_json(manifest_path)
    except (OSError, ValueError, UnicodeDecodeError):
        manifest = None
    if isinstance(manifest, dict) and isinstance(manifest.get("sha256"), str) and manifest["sha256"]:
        return manifest

    # manifest 缺失 / 损坏但 cards 还在：重建 manifest，**不重写 cards**（字节不动，sha256 才稳）
    try:
        cards_bytes = _read_bytes(cards_path)
        payload = json.loads(cards_bytes.decode("utf-8"))
        count = len(payload["words"])
    except (OSError, ValueError, KeyError, TypeError, UnicodeDecodeError):
        # cards 自己也坏了，只好整份重发
        return publish(publish_dir)

    rebuilt = {
        "version": _next_version(publish_dir),
        "book": BOOK_NAME,
        "count": count,
        "sha256": hashlib.sha256(cards_bytes).hexdigest(),
        "updatedAt": _now_iso(),
    }
    _atomic_write(manifest_path, _json_bytes(rebuilt))
    return rebuilt


if __name__ == "__main__":
    import sys

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print(json.dumps(publish(), ensure_ascii=False, indent=2))
