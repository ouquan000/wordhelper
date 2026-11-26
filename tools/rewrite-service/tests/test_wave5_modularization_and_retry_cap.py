"""兼容入口和手动队列质量封顶的离线回放。"""

import ast
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import pipeline
import service
from _isolation import guard_network, isolate  # noqa: E402

isolate(service)
guard_network()
import store


class ModuleContractTest(unittest.TestCase):
    def test_local_import_graph_is_acyclic(self):
        visited = set()

        def visit(name, parents):
            self.assertNotIn(name, parents, " -> ".join((*parents, name)))
            if name in visited:
                return
            tree = ast.parse((ROOT / (name + ".py")).read_text(encoding="utf-8-sig"))
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    imports = [alias.name for alias in node.names]
                elif isinstance(node, ast.ImportFrom):
                    imports = [node.module or ""]
                else:
                    continue
                for target in imports:
                    if (ROOT / (target + ".py")).is_file():
                        visit(target, (*parents, name))
            visited.add(name)

        visit("pipeline", ())
        visit("store", ())
        self.assertIn("quality_retry_policy", visited)

    def test_pipeline_patch_after_import_reaches_generation(self):
        with patch.object(pipeline, "_generate_once", return_value={"derivation": []}) as generate:
            pipeline.generate({"word": "alpha"})
        self.assertEqual(generate.call_args.args[0]["word"], "alpha")

    def test_style_cache_reads_replaced_path_and_loader(self):
        with patch.object(pipeline, "_STYLE_CACHE", {}), patch.object(pipeline, "STYLE_RULES_PATH", "fixture"):
            with patch.object(pipeline, "load_style_rules", return_value={"fixture": True}) as load:
                pipeline._STYLE_CACHE.update(path=None, data=None)
                self.assertEqual(pipeline.style_rules(), {"fixture": True})
                load.assert_called_once_with("fixture")


class QualityCapTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = tmp.name
        self.db = store.Store(self.root).load()
        self.db.add_marks([{"clientId": "wave5", "word": "alpha", "model": "ds-v41-flash"}])
        self.item = self.db.claim()

    def reject(self, candidate):
        return self.db.finish_quality_rejected(
            self.item, candidate, {"reason": "示例评分未过", "method": "shape", "scores": {"score": 6}}
        )

    def test_four_waits_then_failed_receipt_survives_reopen(self):
        for number in range(1, 5):
            with patch.object(store, "_retry_at", return_value="2000-01-01") as retry_at:
                receipt = self.reject(["最佳候选"])
            retry_at.assert_called_once_with(number)
            self.assertEqual(receipt, {"state": "retry_wait", "failures": number, "nextRunAt": "2000-01-01"})
            self.assertEqual(self.db.results_page(0, 10)["results"], [])
            self.item = self.db.claim()
            self.assertEqual(self.item["attempts"], number + 1)

        self.reject([])
        self.reject(["迟到回包"])
        self.db.close()
        resumed = store.Store(self.root).load()
        entry, = resumed.results_page(0, 10)["results"]
        self.assertEqual(entry["approach"], [])
        self.assertEqual(entry["status"], "failed")
        self.assertIn("5 轮上限", entry["reason"])
        self.assertIn("示例评分未过", entry["reason"])
        self.assertEqual(entry["method"], "")
        self.assertEqual(resumed.items[0]["state"], "failed")
        self.assertEqual(resumed.items[0]["nextRunAt"], "")

    def test_fifth_without_valid_candidate_fails(self):
        self.db.items[0].update(attempts=5, failures=4, bestApproach="坏的历史字段")
        self.reject([" "])
        self.assertEqual(self.db.items[0]["state"], "failed")
        self.assertEqual(self.db.items[0]["nextRunAt"], "")
        entry, = self.db.results_page(0, 10)["results"]
        self.assertEqual(entry["status"], "failed")
        self.assertEqual(entry["approach"], [])

    def test_old_attempts_cap_through_service(self):
        self.db.items[0].update(attempts=19, failures=0)
        self.db._save_queue()
        self.db.close()
        resumed = store.Store(self.root).load()
        claimed = resumed.claim()
        out = {"quality_rejected": True, "approach": ["本轮候选"], "reason": "未过"}
        with patch.dict(service._state, store=resumed), patch.object(pipeline, "run", return_value=out):
            with patch.object(service, "log") as log:
                service._work(claimed)
        self.assertFalse(any("后重试" in str(row) for row in log.call_args_list))
        self.assertEqual(resumed.items[0]["state"], "failed")
        self.assertEqual(resumed.results_page(0, 10)["results"][0]["approach"], [])

    def test_old_failures_alone_trigger_cap(self):
        self.db.items[0].update(failures=12, attempts=1)
        self.reject([])
        self.assertEqual(self.db.items[0]["state"], "failed")

    def test_transport_failure_still_retries_at_cap(self):
        self.db.items[0].update(failures=5, attempts=5)
        receipt = self.db.finish_retry(self.item, "timeout")
        self.assertEqual(receipt["state"], "retry_wait")
        self.assertEqual(self.db.results_page(0, 10)["results"], [])

    def test_stopped_generation_cannot_publish_candidate(self):
        self.db.items[0].update(attempts=5)
        self.db.stop(client_id="wave5")
        self.reject(["迟到回包"])
        self.assertEqual(self.db.items[0]["state"], "stopped")
        self.assertEqual(self.db.results_page(0, 10)["results"], [])


if __name__ == "__main__":
    unittest.main()
