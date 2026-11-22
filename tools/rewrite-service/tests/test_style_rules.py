"""v1.5.3 助记大脑换血：风格规则（config/style_rules.json）加载、注入、黑榜扣分与打回。

盯四件事：
1. 配置文件在 → 权重/黑榜/红榜被吃进来，生成/润色/评委三侧提示词都有注入；
2. 配置文件缺 / JSON 坏 / 结构不对 → 一律回落兜底常量，行为与旧版一致（不炸、不误判）；
3. 黑榜四条（慢动作铺垫 / 生僻零件 / 方法标签贴歪 / 保底招当主线）能机械命中并按配置扣分；
4. meaning / polysemy 的路由权重被显著压低，sound 保持第一顺位。
"""

import json
import os
import sys
import tempfile
import unittest

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import pipeline  # noqa: E402


RED_LINE = "① maintain 读 /meɪnˈteɪn/，一口气念出来很像「面瘫」。"
BAD_SLOW = [
    "① 我们先来看 monopoly 这个词，它看起来有点长。",
    "② 说到垄断，很多人会想到价格。",
    "③ 摸拿不离，把东西全摸到自己手里 ——> 垄断；专卖；垄断企业。",
]
BAD_OBSCURE = [
    "① monopoly 拆成 mono + poly 两截。",
    "② poly 来自希腊语 polein「卖」，所以和买卖有关。",
    "③ 一个人独占买卖 ——> 垄断；专卖；垄断企业。",
]


def _scores(value=9):
    return {dim: value for dim in pipeline.JUDGE_DIMENSIONS}


class StyleRulesLoadTest(unittest.TestCase):
    """配置加载与兜底。"""

    def setUp(self):
        self._origin = pipeline.STYLE_RULES_PATH
        self._tmp = tempfile.mkdtemp()

    def tearDown(self):
        pipeline.reload_style_rules(self._origin)

    def _write(self, name, payload):
        path = os.path.join(self._tmp, name)
        with open(path, "w", encoding="utf-8") as fh:
            if isinstance(payload, str):
                fh.write(payload)
            else:
                json.dump(payload, fh, ensure_ascii=False)
        return path

    def test_real_config_is_loaded(self):
        pipeline.reload_style_rules(self._origin)
        self.assertTrue(pipeline.style_active())
        rules = pipeline.style_rules()
        self.assertEqual(rules.get("version"), "1.5.3")
        self.assertTrue(rules.get("red_samples"))
        self.assertTrue(rules.get("black_rules"))

    def test_method_weights_shape(self):
        pipeline.reload_style_rules(self._origin)
        weights = pipeline.method_weights()
        self.assertAlmostEqual(weights["sound"], 73.9, places=1)
        self.assertAlmostEqual(weights["meaning"], 9.4, places=1)
        self.assertAlmostEqual(weights["polysemy"], 0.0, places=1)
        # sound 必须显著高于其它所有招：实测 73.9% 是绝对主力
        self.assertGreater(weights["sound"], max(v for k, v in weights.items() if k != "sound") * 5)
        self.assertEqual(pipeline.method_weight("sound"), 73.9)
        self.assertEqual(pipeline.method_weight("polysemy"), 0.0)

    def test_black_rules_and_reject_set(self):
        pipeline.reload_style_rules(self._origin)
        ids = [row.get("id") for row in pipeline.black_rules()]
        for rule_id in ("slow_start", "advanced_teaches_basic", "root_overreach", "label_mismatch"):
            self.assertIn(rule_id, ids)
        self.assertIn("weak_method", ids)
        self.assertTrue(pipeline.black_rule("slow_start").get("penalty"))
        rejecting = pipeline.reject_on()
        for rule_id in ("slow_start", "advanced_teaches_basic", "root_overreach", "label_mismatch"):
            self.assertIn(rule_id, rejecting)
        self.assertNotIn("weak_method", rejecting)  # 保底招只扣分，不打回

    def test_style_block_injected_into_prompts(self):
        pipeline.reload_style_rules(self._origin)
        block = pipeline.style_block("sound")
        self.assertIn("红榜", block)
        self.assertIn("黑榜", block)
        self.assertIn("73.9", block)
        # 生成 / 润色 / 评委三侧提示词都要带上黑榜铁律
        for name in ("generate", "polish", "judge"):
            self.assertIn("黑榜", pipeline._prompt(name), name)
        for name in ("generate_batch", "judge_batch"):
            try:
                text = pipeline._prompt(name)
            except OSError:
                continue
            self.assertIn("黑榜", text, name)

    def test_missing_config_falls_back(self):
        self.assertFalse(pipeline.reload_style_rules(os.path.join(self._tmp, "nope.json")))
        self.assertFalse(pipeline.style_active())
        self.assertEqual(pipeline.style_block("sound"), "")
        self.assertEqual(pipeline.method_weights(), pipeline.DEFAULT_METHOD_WEIGHTS)
        self.assertEqual(pipeline.method_penalty(), pipeline.DEFAULT_METHOD_PENALTY)
        self.assertEqual(pipeline.reject_on(), pipeline.DEFAULT_REJECT_ON)
        self.assertEqual(pipeline.black_rules(), [])
        # 没配置就没有黑榜判定，旧行为原样保留
        self.assertEqual(pipeline.style_hits({}, {"method": "meaning"}, BAD_SLOW), [])
        scores = _scores()
        gated, hits, rejected = pipeline.style_gate({}, {"method": "meaning"}, BAD_SLOW, scores)
        self.assertEqual((gated, hits, rejected), (scores, [], False))

    def test_corrupt_config_falls_back(self):
        path = self._write("broken.json", "{ not json at all")
        self.assertEqual(pipeline.load_style_rules(path), {})
        self.assertFalse(pipeline.reload_style_rules(path))
        self.assertFalse(pipeline.style_active())

    def test_non_dict_config_falls_back(self):
        path = self._write("list.json", [1, 2, 3])
        self.assertEqual(pipeline.load_style_rules(path), {})
        self.assertFalse(pipeline.reload_style_rules(path))

    def test_custom_config_overrides_defaults(self):
        path = self._write(
            "custom.json",
            {
                "method_weights": {"sound": 90, "meaning": 1},
                "method_penalty": {"meaning": 3},
                "black_rules": [
                    {"id": "slow_start", "name": "慢动作铺垫", "severity": "block",
                     "detail": "前两段必须早破题", "penalty": {"逻辑连贯": 5}},
                ],
                "judge_rules": {"reject_on": ["slow_start"]},
                "few_shot": {"black_header": "【自定义黑榜】"},
            },
        )
        self.assertTrue(pipeline.reload_style_rules(path))
        self.assertEqual(pipeline.method_weight("sound"), 90.0)
        self.assertEqual(pipeline.method_penalty()["meaning"], 3)
        self.assertEqual(len(pipeline.black_rules()), 1)
        self.assertIn("【自定义黑榜】", pipeline.style_block("sound"))
        hits = pipeline.style_hits({}, {"method": "sound"}, BAD_SLOW)
        # 自定义配置只定义了 slow_start，而 BAD_SLOW 没有读音证据，所以
        # label_mismatch（sound 却没有音标/谐音落点）也会命中——这里只断言
        # 自定义规则确实生效，不锁死全部命中项。
        self.assertIn("slow_start", [hit["id"] for hit in hits])
        self.assertTrue(pipeline.style_gate({}, {"method": "sound"}, BAD_SLOW, _scores())[2])
        gated = pipeline.style_penalty_scores(hits, _scores())
        self.assertEqual(gated["逻辑连贯"], 4)


class BlackRuleHitTest(unittest.TestCase):
    """黑榜命中 → 扣分 / 打回。"""

    def setUp(self):
        self._origin = pipeline.STYLE_RULES_PATH
        pipeline.reload_style_rules(self._origin)
        self.assertTrue(pipeline.style_active())

    def tearDown(self):
        pipeline.reload_style_rules(self._origin)

    def test_clean_red_style_has_no_hit(self):
        approach = [RED_LINE, "② 面瘫就是半边脸僵住，笑不了也皱不了。",
                    "③ 表情一直停在原样，让这个状态继续下去 ——> 维持；保持；维护。"]
        hits = pipeline.style_hits({}, {"method": "sound"}, approach)
        self.assertEqual(hits, [])
        scores = _scores()
        gated, misses, rejected = pipeline.style_gate({}, {"method": "sound"}, approach, scores)
        self.assertEqual(misses, [])
        self.assertFalse(rejected)
        self.assertEqual(gated, scores)

    def test_slow_start_is_flagged_and_rejected(self):
        hits = pipeline.style_hits({}, {"method": "sound"}, BAD_SLOW)
        ids = [hit["id"] for hit in hits]
        self.assertIn("slow_start", ids)
        rule = pipeline.black_rule("slow_start")
        scores = _scores()
        gated = pipeline.style_penalty_scores(hits, scores)
        for dim, value in rule["penalty"].items():
            self.assertEqual(gated[dim], scores[dim] - value, dim)
        _gated, _hits, rejected = pipeline.style_gate({}, {"method": "sound"}, BAD_SLOW, scores)
        self.assertTrue(rejected)

    def test_obscure_part_is_flagged(self):
        hits = pipeline.style_hits({}, {"method": "root"}, BAD_OBSCURE)
        self.assertIn("root_overreach", [hit["id"] for hit in hits])
        gated = pipeline.style_penalty_scores(hits, _scores())
        self.assertEqual(gated["拆分成立"], 6)  # 9 - 3
        self.assertEqual(gated["逻辑连贯"], 7)  # 9 - 2

    def test_obscure_part_in_elements_also_flagged(self):
        draft = {"method": "root", "elements": ["mono", "polein"]}
        approach = ["① monopoly 拆成两截。", "② 后半截是卖的意思。",
                    "③ 一个人独占买卖 ——> 垄断。"]
        hits = pipeline.style_hits({}, draft, approach)
        self.assertIn("root_overreach", [hit["id"] for hit in hits])

    def test_label_mismatch_when_sound_has_no_phonetic_anchor(self):
        approach = ["① monopoly 就是一整个买卖。", "② 谁都想独占这一块。",
                    "③ 独占到底 ——> 垄断。"]
        hits = pipeline.style_hits({}, {"method": "sound"}, approach)
        ids = [hit["id"] for hit in hits]
        self.assertIn("label_mismatch", ids)
        gated = pipeline.style_penalty_scores(hits, _scores())
        self.assertEqual(gated["方法选对"], 5)  # 9 - 4

    def test_method_label_ok_when_phonetic_present(self):
        hits = pipeline.style_hits({}, {"method": "sound"}, [RED_LINE])
        self.assertNotIn("label_mismatch", [hit["id"] for hit in hits])

    def test_weak_method_warns_but_does_not_reject(self):
        for method, extra in (("meaning", 1), ("polysemy", 2)):
            approach = ["① 脑子里先放一个画面。", "② 画面推一步就是这个词的意思。",
                        "③ 落到词义上 ——> 意思。"]
            hits = pipeline.style_hits({}, {"method": method}, approach)
            ids = [hit["id"] for hit in hits]
            self.assertIn("weak_method", ids, method)
            gated = pipeline.style_penalty_scores(hits, _scores())
            self.assertEqual(gated["方法选对"], 9 - 1 - extra, method)  # 配置 1 分 + 保底力度
            _gated, _hits, rejected = pipeline.style_gate({}, {"method": method}, approach, _scores())
            self.assertFalse(rejected, method)

    def test_penalty_never_goes_below_zero(self):
        hits = pipeline.style_hits({}, {"method": "sound"}, BAD_SLOW + BAD_OBSCURE)
        gated = pipeline.style_penalty_scores(hits, _scores(1))
        self.assertTrue(all(value >= 0 for value in gated.values()))
        self.assertEqual(gated["逻辑连贯"], 0)

    def test_penalty_keeps_unknown_dimensions_untouched(self):
        hits = pipeline.style_hits({}, {"method": "sound"}, BAD_SLOW)
        gated = pipeline.style_penalty_scores(hits, {"逻辑连贯": 9})
        self.assertEqual(gated, {"逻辑连贯": 7})


class MethodRoutingTest(unittest.TestCase):
    """方法权重：sound 放大，meaning / polysemy 压低。"""

    def setUp(self):
        self._origin = pipeline.STYLE_RULES_PATH
        pipeline.reload_style_rules(self._origin)

    def tearDown(self):
        pipeline.reload_style_rules(self._origin)

    def test_polysemy_weight_is_zero(self):
        self.assertEqual(pipeline.method_weight("polysemy"), 0.0)

    def test_recommended_methods_respect_weights(self):
        ranked = pipeline._recommended_methods({"word": "monopoly"})
        weights = pipeline.method_weights()
        self.assertNotIn("polysemy", ranked)
        scores = [weights.get(method, 0.0) for method in ranked]
        self.assertEqual(scores, sorted(scores, reverse=True))

    def test_route_order_from_config(self):
        order = pipeline.style_rules().get("route_order") or []
        if order:
            self.assertEqual(order[0], "sound")
            self.assertLess(order.index("meaning"), order.index("polysemy"))


if __name__ == "__main__":
    unittest.main()
