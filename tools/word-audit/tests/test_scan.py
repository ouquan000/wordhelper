#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""W6-A 扫描器单测：只碰临时文件，不碰生产词包。"""
from __future__ import annotations

import json
import hashlib
import sys
import tempfile
import unittest
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parents[1]
if str(TOOL_DIR) not in sys.path:
    sys.path.insert(0, str(TOOL_DIR))

import review  # noqa: E402
import scan  # noqa: E402


def card(word: str, approach: list[str], meaning: str = "n. 测试",
         phonetic: str = "/test/", collocations: list[str] | None = None) -> dict:
    return {
        "word": word,
        "phonetic": phonetic,
        "meaning": meaning,
        "example": "",
        "approach": approach,
        "collocations": collocations if collocations is not None else ["test case"],
    }


def normal_card(word: str = "apple", marker: str = "apple") -> dict:
    return card(
        word,
        [
            f"① 先看 {marker} 的读音像「阿婆」",
            "② 阿婆从篮子里递给你一个红苹果",
            "③ 合起来 ——> 苹果",
        ],
        meaning="n. 苹果",
    )


class ScanTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def run_scan(self, entries: list[dict], feedback: list[dict] | None = None) -> dict:
        cards_path = self.root / "cards.json"
        cards_path.write_text(json.dumps({
            "version": 2,
            "book": "六级记忆卡",
            "generatedAt": "2026-01-01 00:00",
            "count": len(entries),
            "words": entries,
        }, ensure_ascii=False), encoding="utf-8")
        feedback_paths: list[Path] = []
        if feedback is not None:
            fb_path = self.root / "feedback.jsonl"
            fb_path.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in feedback) + "\n",
                               encoding="utf-8")
            feedback_paths = [fb_path]
        return scan.scan(cards_path, feedback_paths, book_id=1)

    def categories(self, result: dict, word: str) -> list[str]:
        record = next(r for r in result["words"] if r["word"] == word)
        return [i["category"] for i in record["issues"]]

    def test_normal_word_has_no_issues(self) -> None:
        result = self.run_scan([normal_card()])
        record = result["words"][0]
        self.assertEqual(record["severity"], "none")
        self.assertEqual(record["category"], "ok")
        self.assertEqual(record["issues"], [])
        self.assertEqual(record["status"], "ok")
        self.assertEqual(record["source"]["approach"][2], "③ 合起来 ——> 苹果")

    def test_empty_word_is_p0(self) -> None:
        result = self.run_scan([card("", [
            "① 先看 test 的读音",
            "② 这是第二步",
            "③ 合起来 ——> 测试",
        ])])
        record = result["words"][0]
        self.assertEqual(record["severity"], "P0")
        self.assertIn("empty_word", self.categories(result, ""))

    def test_duplicate_word_is_p0(self) -> None:
        result = self.run_scan([normal_card("apple"), normal_card("Apple", "Apple")])
        self.assertEqual(result["summary"]["issueWords"]["P0"], 2)
        self.assertIn("duplicate_word", self.categories(result, "apple"))
        self.assertIn("duplicate_word", self.categories(result, "Apple"))

    def test_line_count_too_short_and_too_long(self) -> None:
        short = card("short", ["① 只有一步 test ——> 短"])
        long_lines = [f"{scan.CIRCLED_NUMBERS[i]} 第 {i + 1} 步 test" for i in range(6)]
        long_lines[-1] += " ——> 长"
        result = self.run_scan([short, card("long", long_lines)])
        self.assertEqual(self.categories(result, "short"), ["line_count"])
        self.assertEqual(self.categories(result, "long"), ["line_count"])
        self.assertEqual(result["words"][0]["severity"], "P1")

    def test_two_line_approach_is_p2_not_p1(self) -> None:
        result = self.run_scan([card("brief", [
            "① 先看 brief 的读音像「布里夫」",
            "② 布里夫把话写短 ——> 简短的",
        ])])
        record = result["words"][0]
        self.assertIn("short_approach", self.categories(result, "brief"))
        self.assertEqual(record["severity"], "P2")

    def test_internal_term(self) -> None:
        result = self.run_scan([card("apple", [
            "① 先看 apple 的读音像「阿婆」",
            "② 这一招很好用，阿婆递给你一个苹果",
            "③ 合起来 ——> 苹果",
        ])])
        self.assertIn("internal_term", self.categories(result, "apple"))
        self.assertEqual(result["words"][0]["severity"], "P1")

    def test_internal_method_jargon(self) -> None:
        result = self.run_scan([
            card("apple", [
                "① 先看 apple 的读音像「阿婆」",
                "② 于是挑一招含义联想法，阿婆递给你一个苹果",
                "③ 合起来 ——> 苹果",
            ]),
            card("pear", [
                "① 先看 pear 的读音像「皮儿」",
                "② 走含义联想，皮儿里包着甜梨",
                "③ 合起来 ——> 梨",
            ]),
        ])
        self.assertIn("internal_term", self.categories(result, "apple"))
        self.assertIn("internal_term", self.categories(result, "pear"))
        self.assertEqual(result["words"][0]["severity"], "P1")
        self.assertEqual(result["words"][1]["severity"], "P1")

    def test_arrow_missing(self) -> None:
        result = self.run_scan([card("apple", [
            "① 先看 apple 的读音像「阿婆」",
            "② 阿婆从篮子里递给你一个红苹果",
            "③ 合起来就是苹果",
        ])])
        self.assertIn("arrow_missing", self.categories(result, "apple"))
        self.assertEqual(result["words"][0]["severity"], "P1")

    def test_penultimate_arrow_is_two_line_note_not_hard_failure(self) -> None:
        result = self.run_scan([card("apple", [
            "① 先看 apple 的读音像「阿婆」",
            "② 阿婆从篮子里递给你一个红苹果",
            "③ 合起来 ——> 苹果",
            "注：a 在古英语里也写作 æppel",
        ])])
        categories = self.categories(result, "apple")
        self.assertIn("arrow_penultimate", categories)
        self.assertNotIn("arrow_missing", categories)
        self.assertEqual(result["words"][0]["severity"], "P2")

    def test_pos_mixed(self) -> None:
        result = self.run_scan([normal_card("support", "support") | {
            "meaning": "v. 支持；n. 支持"
        }])
        self.assertIn("pos_mixed", self.categories(result, "support"))
        self.assertEqual(result["words"][0]["severity"], "P1")

    def test_natural_placeholder_word_is_not_flagged(self) -> None:
        result = self.run_scan([card("pending", [
            "① 先看 pending 的读音像「喷灯」",
            "② 喷灯还没点着，事情处在占位悬着的状态",
            "③ 合起来 ——> 待定的、未完成的",
        ])])
        self.assertNotIn("placeholder", self.categories(result, "pending"))
        self.assertEqual(result["words"][0]["issues"], [])

    def test_feedback_protection_and_conflict(self) -> None:
        current = normal_card("apple")
        text = "\n".join(current["approach"])
        feedback = [
            {"clientId": "c-up", "rating": "up", "wordId": 1, "bookId": 1,
             "word": "apple", "approach": text, "instruction": ""},
            {"clientId": "c-down", "rating": "down", "wordId": 1, "bookId": 1,
             "word": "apple", "approach": text, "instruction": ""},
        ]
        result = self.run_scan([current], feedback=feedback)
        record = result["words"][0]
        self.assertTrue(record["protected"])
        self.assertEqual(record["protection"]["upExact"], 1)
        self.assertEqual(record["protection"]["downExact"], 1)
        self.assertTrue(record["protection"]["conflict"])
        self.assertEqual(record["status"], "manual_review")
        self.assertIn("history_conflict", self.categories(result, "apple"))

    def test_feedback_up_old_version_requires_manual_review(self) -> None:
        current = normal_card("apple")
        feedback = [{
            "clientId": "c-up-old", "rating": "up", "wordId": 1, "bookId": 1,
            "word": "apple", "approach": "① 旧版第一句\n② 旧版第二句\n③ 旧版 ——> 苹果",
            "instruction": "",
        }]
        result = self.run_scan([current], feedback=feedback)
        record = result["words"][0]
        self.assertFalse(record["protected"])
        self.assertEqual(record["protection"]["upOtherVersion"], 1)
        self.assertEqual(record["protection"]["upOtherVersionClients"], ["c-up-old"])
        detail = record["protection"]["matchedFeedback"][0]
        self.assertEqual(detail["textMatch"], "other_version")
        self.assertTrue(detail["textSha256"])
        self.assertIn("旧版第一句", detail["approach"])
        self.assertEqual(record["status"], "manual_review")
        self.assertIn("history_protection_review", self.categories(result, "apple"))

    def test_test_like_feedback_is_counted_but_not_protected(self) -> None:
        current = normal_card("apple")
        feedback = [{
            "clientId": "test-1", "rating": "up", "wordId": 1, "bookId": 1,
            "word": "apple", "approach": "\n".join(current["approach"]), "instruction": "",
        }]
        result = self.run_scan([current], feedback=feedback)
        record = result["words"][0]
        self.assertFalse(record["protected"])
        self.assertEqual(record["history"]["records"], 0)
        self.assertEqual(result["summary"]["feedback"]["testLike"], 1)
        self.assertEqual(result["summary"]["feedback"]["bookScoped"]["testLike"], 1)

    def test_feedback_book_scope_stats(self) -> None:
        current = normal_card("apple")
        text = "\n".join(current["approach"])
        feedback = [
            {"clientId": "c-book1", "rating": "up", "wordId": 1, "bookId": 1,
             "word": "apple", "approach": text, "instruction": ""},
            {"clientId": "c-book2", "rating": "up", "wordId": 1, "bookId": 2,
             "word": "apple", "approach": text, "instruction": ""},
        ]
        result = self.run_scan([current], feedback=feedback)
        record = result["words"][0]
        self.assertTrue(record["protected"])
        self.assertEqual(record["protection"]["matchedUpClients"], ["c-book1"])
        self.assertEqual(result["summary"]["feedback"]["allBooks"]["records"], 2)
        self.assertEqual(result["summary"]["feedback"]["allBooks"]["explicitOtherBook"], 1)
        self.assertEqual(result["summary"]["feedback"]["bookScoped"]["records"], 1)
        self.assertEqual(result["summary"]["feedback"]["bookScoped"]["explicitOtherBook"], 1)
        self.assertEqual(result["summary"]["feedback"]["bookScoped"]["distinctWords"], 1)

    def test_hash_fields_distinguish_raw_and_normalized(self) -> None:
        payload = {
            "version": 2,
            "book": "六级记忆卡",
            "count": 1,
            "words": [normal_card("apple")],
        }
        text = json.dumps(payload, ensure_ascii=False, indent=1)
        raw = text.replace("\n", "\r\n").encode("utf-8")
        cards_path = self.root / "cards.json"
        cards_path.write_bytes(raw)
        result = scan.scan(cards_path, [], book_id=1)
        meta = result["meta"]
        normalized = raw.decode("utf-8-sig").replace("\r\n", "\n")
        self.assertEqual(meta["cardsFileSha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(meta["cardsTextSha256"], hashlib.sha256(normalized.encode("utf-8")).hexdigest())
        self.assertNotEqual(meta["cardsFileSha256"], meta["cardsTextSha256"])

    def test_review_ok_samples_and_p2_full_list(self) -> None:
        result = self.run_scan([
            normal_card(),
            card("bad", ["① 一步 test"]),
            card("brief", [
                "① 先看 brief 的读音像「布里夫」",
                "② 布里夫把话写短 ——> 简短的",
            ]),
        ])
        md, review_obj = review.build_review(result, per_category=1, ok_samples_limit=1)
        self.assertEqual(len(review_obj["okSamples"]), 1)
        sample = review_obj["okSamples"][0]
        self.assertEqual(sample["word"], "apple")
        self.assertTrue(sample["book"])
        self.assertEqual(sample["stratum"]["book"], sample["book"])
        self.assertIn("词表位置", sample["reason"])
        self.assertIn("approach", sample)
        self.assertIn("完整列表见 JSON", review.compact_words(
            [{"word": f"w{i}", "lineNo": i} for i in range(3)], limit=1))
        self.assertIn("未做语义判定", md)
        self.assertEqual(len(review_obj["p2"]), 1)
        self.assertEqual(review_obj["p2"][0]["word"], "brief")

    def test_summary_counts_and_report(self) -> None:
        result = self.run_scan([normal_card(), card("bad", ["① 一步 test"])])
        self.assertEqual(result["summary"]["totalWords"], 2)
        self.assertEqual(result["summary"]["okWords"], 1)
        self.assertEqual(result["summary"]["issueWords"]["P1"], 1)
        md = scan.render_markdown(result)
        self.assertIn("实际扫描：**2** 词", md)
        self.assertIn("## 9. 复现", md)
        self.assertIn("未覆盖", md)


if __name__ == "__main__":
    unittest.main()
