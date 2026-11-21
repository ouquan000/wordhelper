#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""card_publisher 自测（脚本式，全部在临时目录里跑，不碰真实 published/）

跑法：
    python test_cards_publish.py
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import card_publisher  # noqa: E402

PASSED = []
FAILED = []


def check(name, cond, detail=""):
    if cond:
        PASSED.append(name)
        print("  [PASS] %s %s" % (name, detail))
    else:
        FAILED.append(name)
        print("  [FAIL] %s %s" % (name, detail))


def write_json(path, payload):
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)


def read_json(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


# ---------- 夹具：3 个种子词条 + 一批 results（含各种该被忽略的） ----------
def make_seed():
    return {
        "version": 3,
        "book": "六级记忆卡",
        "generatedAt": "2026-01-01 00:00",
        "count": 3,
        "words": [
            {
                "word": "alpha",
                "phonetic": "/ˈælfə/",
                "meaning": "n. 阿尔法",
                "example": "Alpha is first.\n阿尔法排第一。",
                "approach": ["种子里 alpha 的旧思路"],
                "collocations": ["alpha test —— 内测"],
            },
            {
                "word": "beta",
                "phonetic": "/ˈbiːtə/",
                "meaning": "n. 贝塔",
                "example": "Beta comes second.\n贝塔排第二。",
                "approach": ["种子里 beta 的旧思路"],
                "collocations": ["beta version —— 测试版"],
            },
            {
                "word": "gamma",
                "phonetic": "/ˈɡæmə/",
                "meaning": "n. 伽马",
                "example": "Gamma comes third.\n伽马排第三。",
                "approach": ["种子里 gamma 的旧思路"],
                "collocations": ["gamma ray —— 伽马射线"],
            },
        ],
    }


def make_entry(word, rev, approach, method="root", **extra):
    entry = {
        "seq": rev,
        "word": word,
        "bookId": 1,
        "rev": rev,
        "approach": approach,
        "method": method,
        "prev": [],
        "updatedAt": "2026-01-01T00:00:00+08:00",
        "status": "ok",
        "unverified": False,
    }
    entry.update(extra)
    return entry


def write_results(results_dir):
    """5 个文件：alpha 多条同词、beta 含书名不符、gamma 缺 book、zeta 不在种子里、1 个坏 JSON。"""
    os.makedirs(results_dir, exist_ok=True)
    # alpha：rev1 合格、rev3 合格（应胜出）、rev9 但 unverified=true、rev8 但 status=failed
    write_json(
        os.path.join(results_dir, "alpha.json"),
        {
            "word": "alpha",
            "entries": [
                make_entry("alpha", 1, ["①rev1 旧思路"], method="root",
                           book="六级记忆卡", updatedAt="2026-01-01T00:00:00+08:00"),
                make_entry("alpha", 3, ["①rev3 新思路", "②rev3 第二条"], method="shape",
                           book="六级记忆卡", updatedAt="2026-01-02T00:00:00+08:00"),
                make_entry("alpha", 9, ["①未验证的高 rev 不该赢"], method="root",
                           book="六级记忆卡", unverified=True),
                make_entry("alpha", 8, ["①失败的高 rev 不该赢"], method="root",
                           book="六级记忆卡", status="failed"),
            ],
        },
    )
    # beta：rev2 合格；rev99 书名是四级，不该被合并
    write_json(
        os.path.join(results_dir, "beta.json"),
        {
            "word": "beta",
            "entries": [
                make_entry("beta", 2, ["①beta 新思路"], method="affix",
                           book="六级记忆卡"),
                make_entry("beta", 99, ["①四级书的思路不该进六级"], method="root",
                           book="四级记忆卡"),
            ],
        },
    )
    # gamma：连 book 字段都没有（= 不限定书名），应接受
    write_json(
        os.path.join(results_dir, "gamma.json"),
        {
            "word": "gamma",
            "entries": [make_entry("gamma", 1, ["①gamma 新思路"], method="root")],
        },
    )
    # zeta：合格但种子里没有 → 不许新增词
    write_json(
        os.path.join(results_dir, "zeta.json"),
        {
            "word": "zeta",
            "entries": [make_entry("zeta", 1, ["①不该出现的词"], method="root",
                                   book="六级记忆卡")],
        },
    )
    # 坏文件：半截 JSON，必须只跳过、不崩
    with open(os.path.join(results_dir, "broken.json"), "w", encoding="utf-8") as fh:
        fh.write('{"word": "delta", "entries": [{"seq": 1,')


def words_by_name(payload):
    return {entry.get("word"): entry for entry in payload.get("words", [])}


def test_publish(tmp):
    print("\n[1] publish：过滤条件 / 同词取 rev 最大 / manifest / .bak")
    seed_path = os.path.join(tmp, "seed", "cet6_cards.json")
    results_dir = os.path.join(tmp, "results")
    out_dir = os.path.join(tmp, "published", "cards")
    write_json(seed_path, make_seed())
    write_results(results_dir)

    with mock.patch.object(card_publisher, "SEED_PATH", seed_path), mock.patch.object(
        card_publisher, "RESULTS_DIR", results_dir
    ):
        first = card_publisher.publish(out_dir)

    cards_path = os.path.join(out_dir, card_publisher.CARDS_FILENAME)
    manifest_path = os.path.join(out_dir, card_publisher.MANIFEST_FILENAME)

    check("发布文件已写出", os.path.isfile(cards_path), cards_path)
    check("manifest 已写出", os.path.isfile(manifest_path), manifest_path)
    check("首次 publish version == 1", first.get("version") == 1, repr(first.get("version")))
    check("返回值带 merged 统计", first.get("merged") == 3, repr(first.get("merged")))

    stats = first.get("stats") or {}
    check("stats.files 扫到 5 个 results 文件", stats.get("files") == 5, repr(stats))
    check("坏 JSON 只跳过不崩（broken_files == 1）", stats.get("broken_files") == 1, repr(stats))

    with open(cards_path, "rb") as fh:
        cards_bytes = fh.read()
    manifest = read_json(manifest_path)
    payload = read_json(cards_path)
    words = words_by_name(payload)

    check("manifest 只有约定的 5 个字段",
          sorted(manifest.keys()) == ["book", "count", "sha256", "updatedAt", "version"],
          repr(sorted(manifest.keys())))
    check("manifest.book 固定为 六级记忆卡", manifest.get("book") == "六级记忆卡",
          repr(manifest.get("book")))
    check("manifest.count == 发布 JSON 的 words 数",
          manifest.get("count") == len(payload.get("words", [])) == 3,
          "%r vs %r" % (manifest.get("count"), len(payload.get("words", []))))
    check("manifest.sha256 == 发布文件字节的 sha256",
          manifest.get("sha256") == hashlib.sha256(cards_bytes).hexdigest(),
          repr(manifest.get("sha256")))
    check("manifest.updatedAt 带时区偏移（+08:00 之类）",
          isinstance(manifest.get("updatedAt"), str)
          and ("+" in manifest["updatedAt"][-6:] or manifest["updatedAt"].endswith("Z")),
          repr(manifest.get("updatedAt")))

    # ---- 合并规则
    alpha = words.get("alpha", {})
    check("同词多条取 rev 最大的合格记录",
          alpha.get("approach") == ["①rev3 新思路", "②rev3 第二条"], repr(alpha.get("approach")))
    check("method 跟着胜出记录写进去", alpha.get("method") == "shape", repr(alpha.get("method")))
    check("unverified=true / status!=ok 的高 rev 记录被忽略",
          not any("不该赢" in str(item) for item in alpha.get("approach") or []),
          repr(alpha.get("approach")))

    check("种子其它字段原样保留（phonetic/meaning/example/collocations）",
          alpha.get("phonetic") == "/ˈælfə/"
          and alpha.get("meaning") == "n. 阿尔法"
          and alpha.get("example") == "Alpha is first.\n阿尔法排第一。"
          and alpha.get("collocations") == ["alpha test —— 内测"],
          repr({k: alpha.get(k) for k in ("phonetic", "meaning", "example", "collocations")}))

    beta = words.get("beta", {})
    check("book 不是 六级记忆卡 的记录不合并（rev99 四级书被丢掉）",
          beta.get("approach") == ["①beta 新思路"] and beta.get("method") == "affix",
          "%r / %r" % (beta.get("approach"), beta.get("method")))

    gamma = words.get("gamma", {})
    check("缺 book 字段的记录照常合并", gamma.get("approach") == ["①gamma 新思路"],
          repr(gamma.get("approach")))

    check("种子里没有的词不新增（zeta 不在发布包里）", "zeta" not in words, repr(list(words)))
    check("words 数量没变（3 条）", len(payload.get("words", [])) == 3, repr(len(payload.get("words", []))))

    check("输出是 CRLF + 2 空格缩进 + 末尾换行",
          b"\r\n" in cards_bytes and cards_bytes.endswith(b"\r\n"),
          repr(cards_bytes[:40]))
    check("没有 .tmp 残留", not os.path.exists(cards_path + ".tmp")
          and not os.path.exists(manifest_path + ".tmp"), "")
    check("首次没有 .bak（之前没文件）",
          not os.path.exists(cards_path + ".bak") and not os.path.exists(manifest_path + ".bak"), "")

    # ---- 第二次发布：version 递增 + .bak 兜底
    with mock.patch.object(card_publisher, "SEED_PATH", seed_path), mock.patch.object(
        card_publisher, "RESULTS_DIR", results_dir
    ):
        second = card_publisher.publish(out_dir)

    check("第二次 publish version 递增到 2", second.get("version") == 2, repr(second.get("version")))
    check("第二次 publish 有 cards .bak", os.path.isfile(cards_path + ".bak"), cards_path + ".bak")
    check("第二次 publish 有 manifest .bak",
          os.path.isfile(manifest_path + ".bak"), manifest_path + ".bak")
    with open(cards_path + ".bak", "rb") as fh:
        bak_bytes = fh.read()
    check(".bak 是第一版的内容（兜底可回滚）", bak_bytes == cards_bytes,
          "%d vs %d 字节" % (len(bak_bytes), len(cards_bytes)))


def test_ensure(tmp):
    print("\n[2] ensure_published：缺文件自动发布 / manifest 缺失只重建 manifest")
    seed_path = os.path.join(tmp, "seed2", "cet6_cards.json")
    results_dir = os.path.join(tmp, "results2")
    out_dir = os.path.join(tmp, "published2", "cards")
    write_json(seed_path, make_seed())
    write_results(results_dir)

    with mock.patch.object(card_publisher, "SEED_PATH", seed_path), mock.patch.object(
        card_publisher, "RESULTS_DIR", results_dir
    ):
        # 2.1 空目录：自动 publish 初始化
        manifest = card_publisher.ensure_published(out_dir)
        cards_path = os.path.join(out_dir, card_publisher.CARDS_FILENAME)
        manifest_path = os.path.join(out_dir, card_publisher.MANIFEST_FILENAME)
        check("空目录 ensure 后发布文件存在", os.path.isfile(cards_path) and os.path.isfile(manifest_path),
              out_dir)
        check("空目录 ensure 返回 version 1", manifest.get("version") == 1, repr(manifest.get("version")))
        check("空目录 ensure 返回 count 3", manifest.get("count") == 3, repr(manifest.get("count")))

        with open(cards_path, "rb") as fh:
            before_bytes = fh.read()

        # 2.2 已有发布包：直接读 manifest，不动 cards
        again = card_publisher.ensure_published(out_dir)
        with open(cards_path, "rb") as fh:
            after_bytes = fh.read()
        check("已存在时 ensure 直接返回同一 manifest",
              again.get("version") == manifest.get("version")
              and again.get("sha256") == manifest.get("sha256"),
              repr(again.get("version")))
        check("已存在时 ensure 不重写 cards", before_bytes == after_bytes,
              "%d vs %d 字节" % (len(before_bytes), len(after_bytes)))

        # 2.3 manifest 被删：只重建 manifest，cards 字节不变
        os.remove(manifest_path)
        rebuilt = card_publisher.ensure_published(out_dir)
        with open(cards_path, "rb") as fh:
            rebuilt_cards = fh.read()
        check("manifest 缺失时重建出来", os.path.isfile(manifest_path), manifest_path)
        check("重建 manifest 时 cards 不重写", rebuilt_cards == before_bytes,
              "%d vs %d 字节" % (len(rebuilt_cards), len(before_bytes)))
        check("重建的 manifest sha256 对得上 cards 字节",
              rebuilt.get("sha256") == hashlib.sha256(before_bytes).hexdigest(),
              repr(rebuilt.get("sha256")))
        check("重建的 manifest count == words 数", rebuilt.get("count") == 3,
              repr(rebuilt.get("count")))
        check("重建的 manifest book 仍固定", rebuilt.get("book") == "六级记忆卡",
              repr(rebuilt.get("book")))


def main():
    print("== card_publisher 自测 ==")
    print("模块：%s" % card_publisher.__file__)
    with tempfile.TemporaryDirectory(prefix="cardpub_test_") as tmp:
        test_publish(os.path.join(tmp, "case1"))
        test_ensure(os.path.join(tmp, "case2"))
    print("\n%d PASSED / %d FAILED" % (len(PASSED), len(FAILED)))
    return 0 if not FAILED else 1


if __name__ == "__main__":
    sys.exit(main())
