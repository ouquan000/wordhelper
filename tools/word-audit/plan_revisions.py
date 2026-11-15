#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""W6-B 定向修订计划：只读 scan.json，生成可追踪的修订队列。

不调用模型、不改词包。输出每条的旧正文、命中的问题、历史反馈和状态，
后续修订工具按 word 回填 candidate，状态从 pending -> candidate -> approved/rejected。
"""
from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path
from typing import Any

TARGET_CATEGORIES = {"history_down_current", "history_conflict"}


def plan(scan: dict[str, Any]) -> dict[str, Any]:
    items = []
    for record in scan.get("words", []):
        issues = record.get("issues") or []
        cats = {str(i.get("category")) for i in issues}
        hit = sorted(cats & TARGET_CATEGORIES)
        if not hit:
            continue
        source = record.get("source") or {}
        items.append({
            "word": record.get("word"),
            "book": record.get("book"),
            "lineNo": record.get("lineNo"),
            "severity": record.get("severity"),
            "status": "manual_review" if record.get("protected") else "pending",
            "categories": hit,
            "allIssues": [i.get("category") for i in issues],
            "protected": bool(record.get("protected")),
            "oldApproach": source.get("approach") or [],
            "oldMeaning": source.get("meaning") or "",
            "feedbackMatches": record.get("matchedFeedback") or [],
            "candidateApproach": None,
            "reviewNote": "",
        })
    items.sort(key=lambda x: (str(x.get("book") or ""), int(x.get("lineNo") or 0)))
    return {
        "schema": "word-audit-revision-plan/v1",
        "generatedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
        "source": "scan.json",
        "counts": {
            "total": len(items),
            "pending": sum(1 for i in items if i["status"] == "pending"),
            "manual_review": sum(1 for i in items if i["status"] == "manual_review"),
            "protected": sum(1 for i in items if i["protected"]),
        },
        "items": items,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--scan", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    scan = json.loads(Path(args.scan).read_text(encoding="utf-8-sig"))
    data = plan(scan)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"pending={data['counts']['pending']} protected={data['counts']['protected']} -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
