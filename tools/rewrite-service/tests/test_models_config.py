"""v1.5.3：config/models.json 加载、兜底与下发形状。

手机端会消费服务端下发的型号列表（GET /models → providers.describe()），
所以这里盯三件事：配置被吃进来、enabled/weight 生效、配置缺失或坏掉时
一律回落代码常量（老版本行为不变，service.py 不许改）。
"""

import json
import os
import sys
import tempfile
import unittest

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SERVICE_DIR not in sys.path:
    sys.path.insert(0, SERVICE_DIR)

import providers  # noqa: E402


class ModelsConfigTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.mkdtemp()
        self._defaults = dict(providers._DEFAULT_MODELS)
        self._default_judges = tuple(providers._DEFAULT_JUDGE_CANDIDATES)
        self._default_base = dict(providers._DEFAULT_PROVIDER_BASE)

    def tearDown(self):
        # 一律恢复成「按真实 config/models.json 重新加载」，避免污染其它测试。
        providers.reload_config()

    def _write(self, name, payload):
        path = os.path.join(self._tmp, name)
        with open(path, "w", encoding="utf-8") as fh:
            if isinstance(payload, str):
                fh.write(payload)
            else:
                json.dump(payload, fh, ensure_ascii=False)
        return path

    def test_real_config_is_loaded(self):
        self.assertTrue(providers.reload_config())
        self.assertTrue(providers.config_active())
        self.assertIn("ds-v41-flash", providers.MODELS)
        for model_id, spec in providers.MODELS.items():
            self.assertTrue(spec.get("provider"), model_id)
            self.assertTrue(spec.get("model"), model_id)
            self.assertIn("weight", spec, model_id)
            self.assertIn("tier", spec, model_id)
            self.assertIsInstance(spec.get("enabled"), bool, model_id)

    def test_routing_order_is_weight_desc(self):
        providers.reload_config()
        rows = providers.routing_order()
        self.assertTrue(rows)
        weights = [row["weight"] for row in rows]
        self.assertEqual(weights, sorted(weights, reverse=True))
        for row in rows:
            self.assertIn("id", row)
            self.assertIn("name", row)
            self.assertIn("tier", row)

    def test_describe_shape_for_phone(self):
        providers.reload_config()
        rows = providers.describe()
        self.assertTrue(rows)
        first = rows[0]
        for key in ("id", "name", "provider", "reasoning"):
            self.assertIn(key, first)
        self.assertEqual([row["id"] for row in rows], [row["id"] for row in providers.describe()])

    def test_resolve_unknown_raises(self):
        providers.reload_config()
        with self.assertRaises(providers.ProviderError):
            providers.resolve("no-such-model")

    def test_resolve_known_model(self):
        providers.reload_config()
        target = providers.resolve("ds-v41-flash")
        self.assertEqual(target["provider"], "wb")
        self.assertEqual(target["model"], "cn:deepseek-v4.1-flash")
        self.assertEqual(target["reasoning"], "medium")

    def test_flywheel_default_effort_is_medium(self):
        providers.reload_config()
        self.assertEqual(providers.MODELS["ds-v41-flash"]["reasoning"], "medium")
        self.assertEqual(providers.resolve("ds-v41-flash")["reasoning"], "medium")

    def test_missing_config_falls_back_to_constants(self):
        path = os.path.join(self._tmp, "nope.json")
        self.assertEqual(providers.load_models_config(path), {})
        self.assertFalse(providers.reload_config(path))
        # 注意：config_active() 无参时读的是模块级 MODELS_CONFIG_PATH（真实配置存在），
        # 不反映刚才传入的坏路径；这里只断言坏路径没生效、清单回落常量。
        self.assertEqual(set(providers.MODELS), set(self._defaults))
        self.assertEqual(tuple(providers.JUDGE_CANDIDATES), self._default_judges)
        # PROVIDER_BASE 仍要指向默认地址（test_wb_gate 会就地改写 PROVIDER_BASE["wb"]）
        self.assertEqual(providers.PROVIDER_BASE["wb"], self._default_base["wb"])
        self.assertIsInstance(providers.PROVIDER_BASE, dict)

    def test_corrupt_config_falls_back(self):
        path = self._write("broken.json", "{ nope")
        self.assertEqual(providers.load_models_config(path), {})
        self.assertFalse(providers.apply_config({}))
        self.assertEqual(set(providers.MODELS), set(self._defaults))

    def test_non_dict_config_falls_back(self):
        path = self._write("list.json", [1, 2, 3])
        self.assertEqual(providers.load_models_config(path), {})
        self.assertFalse(providers.apply_config({}))

    def test_disabled_model_hidden_from_describe(self):
        payload = providers.load_models_config()
        rows = payload.get("models") or []
        if not rows:
            self.skipTest("没有 config/models.json，跳过")
        payload = json.loads(json.dumps(payload))
        payload["models"][0]["enabled"] = False
        hidden = payload["models"][0]["id"]
        path = self._write("disabled.json", payload)
        self.assertTrue(providers.reload_config(path))
        ids = [row["id"] for row in providers.describe()]
        self.assertNotIn(hidden, ids)
        self.assertIn(hidden, providers.MODELS)  # 仍在表里，只是不下发
        self.assertNotIn(hidden, [row["id"] for row in providers.routing_order()])

    def test_bad_row_is_dropped_not_fatal(self):
        path = self._write(
            "partial.json",
            {
                "models": [
                    {"id": "good", "display": "Good", "provider": "wb",
                     "model": "cn:good", "reasoning": "high", "tier": "fast", "weight": 7},
                    {"id": "", "provider": "wb", "model": "cn:x"},
                    {"display": "No Model", "provider": "wb"},
                    "not a dict",
                ],
                "judge_candidates": [{"provider": "wb", "model": "cn:judge"}],
                "provider_base": {"wb": "http://example.test/v1"},
            },
        )
        self.assertTrue(providers.reload_config(path))
        self.assertEqual(list(providers.MODELS), ["good"])
        self.assertEqual(providers.MODELS["good"]["name"], "Good")
        self.assertEqual(providers.routing_order()[0]["weight"], 7.0)
        self.assertEqual(providers.PROVIDER_BASE["wb"], "http://example.test/v1")
        # 配置里没给的 provider base 仍保留默认值
        self.assertIn("portdan-gpt", providers.PROVIDER_BASE)

    def test_provider_base_stays_mutable(self):
        providers.reload_config()
        original = providers.PROVIDER_BASE["wb"]
        providers.PROVIDER_BASE["wb"] = "http://127.0.0.1:1/v1"
        try:
            self.assertEqual(providers.PROVIDER_BASE["wb"], "http://127.0.0.1:1/v1")
        finally:
            providers.PROVIDER_BASE["wb"] = original


if __name__ == "__main__":
    unittest.main()
