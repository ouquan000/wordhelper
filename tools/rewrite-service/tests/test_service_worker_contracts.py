"""手动队列与闲时洗词入口的离线兼容契约。"""

import ast
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import service
from _isolation import guard_network, isolate  # noqa: E402

isolate(service)
guard_network()
import worker


class ImportGraphTests(unittest.TestCase):
    def test_local_dependencies_from_both_facades_are_acyclic(self):
        visited = set()

        def visit(name, parents):
            self.assertNotIn(name, parents, " -> ".join((*parents, name)))
            if name in visited:
                return
            source = (ROOT / (name + ".py")).read_text(encoding="utf-8-sig")
            for node in ast.walk(ast.parse(source)):
                if isinstance(node, ast.Import):
                    modules = [alias.name for alias in node.names]
                elif isinstance(node, ast.ImportFrom):
                    modules = [node.module] if node.module else [alias.name for alias in node.names]
                else:
                    continue
                for module in modules:
                    target = module.split(".")[0]
                    if name.startswith(("service_", "worker_")):
                        self.assertNotIn(target, {"service", "worker"})
                    if (ROOT / (target + ".py")).is_file():
                        visit(target, (*parents, name))
            visited.add(name)

        visit("service", ())
        visit("worker", ())
        self.assertTrue({"service_execution", "service_handlers", "worker_state",
                         "worker_candidates", "worker_run"}.issubset(visited))


class ServiceRoutesTests(unittest.TestCase):
    def test_marks_reads_replaced_state_and_log(self):
        db = Mock()
        db.add_marks.return_value = (["ok"], [])
        with patch.object(service, "_state", {"ready": True, "store": db}), patch.object(service, "log") as log:
            status, mime, raw = service.respond("POST", "/rewrite/marks", b'{"marks":[{"word":"alpha"}]}')
        self.assertEqual((status, mime), (200, "application/json; charset=utf-8"))
        self.assertEqual(json.loads(raw), {"accepted": ["ok"], "rejected": []})
        db.add_marks.assert_called_once_with([{"word": "alpha"}])
        log.assert_called_once_with("收标 1 条：接受 1 拒绝 0")

    def test_method_fallthrough_and_validation(self):
        with patch.object(service, "startup"):
            self.assertIsNone(service.respond("GET", "/rewrite/marks"))
            self.assertIsNone(service.respond("POST", "/models"))
            status, _, raw = service.respond("POST", "/rewrite/marks", b"{}")
        self.assertEqual(status, 400)
        self.assertEqual(json.loads(raw), {"error": "marks 必须是数组"})


class ConsoleReadOnlyRouteTests(unittest.TestCase):
    def test_worker_preview_is_read_only_and_shape_is_stable(self):
        store = Mock()
        rows = [{"word": "alpha", "wordId": 1, "book": "六级记忆卡",
                 "meaning": "第一", "why": "new", "new": True}]
        with patch.object(service, "startup"), patch.object(
            service, "_state", {"store": store}
        ), patch.object(service.worker_mod, "attach") as attach, patch.object(
            service.worker_mod, "preview", return_value=rows
        ):
            status, mime, raw = service.respond("GET", "/rewrite/worker/preview", b"")
        self.assertEqual((status, mime), (200, "application/json; charset=utf-8"))
        self.assertEqual(json.loads(raw), {"ok": True, "candidates": rows, "count": 1})
        attach.assert_called_once()
        self.assertNotIn("path", json.dumps(json.loads(raw)))

    def test_anchors_count_exposes_only_a_number(self):
        with patch.object(service, "startup"), patch.object(
            service.familiar_anchors, "index", return_value={"alpha": {}, "beta": {}}
        ):
            status, _, raw = service.respond("GET", "/rewrite/anchors/count", b"")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(raw), {"ok": True, "count": 2, "book": "六级记忆卡"})
        self.assertNotIn("sync.json", raw.decode("utf-8"))

    def test_toggle_forwards_include_review_override(self):
        with patch.object(service, "startup"), patch.object(
            service, "_state", {"store": Mock()}
        ), patch.object(service.worker_mod, "attach"), patch.object(
            service.worker_mod, "toggle", return_value={"enabled": True}
        ) as toggle:
            status, _, raw = service.respond(
                "POST", "/rewrite/worker/toggle",
                json.dumps({"enabled": True, "includeReview": True}).encode("utf-8"),
            )
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(raw)["enabled"], True)
        toggle.assert_called_once()
        self.assertTrue(toggle.call_args.kwargs["review"])


class WorkerBoundaryTests(unittest.TestCase):
    def test_runtime_restores_legacy_fields_and_saves_to_replaced_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "worker_state.json"
            path.write_text('{"enabled":false,"washed":"4","runs":[null,"old"],"done":{"a":{"seq":2}}}',
                            encoding="utf-8")
            with patch.object(worker, "_state_path", return_value=str(path)), patch.object(
                worker, "_runtime_cache", {"path": "", "data": None}
            ), patch.object(worker, "_now", return_value="stamp"):
                restored = worker._load_runtime()
                self.assertEqual(restored, {"enabled": False, "daily": {}, "washed": 4, "runs": ["old"],
                                            "done": {"a": {"seq": 2}}, "lastRun": {}, "lastError": ""})
                self.assertFalse(worker.enabled_now({"enabled": True}, restored))
                self.assertIs(worker._load_runtime(), restored)
                worker._save_runtime(restored)
                self.assertEqual(json.loads(path.read_text(encoding="utf-8-sig")), dict(restored, updatedAt="stamp"))
                with patch.object(worker, "_runtime_cache", {"path": "", "data": None}):
                    self.assertEqual(worker._load_runtime()["done"], {"a": {"seq": 2}})

    def test_lock_acquired_after_candidate_selection_skips_runner_and_budget(self):
        db = Mock()
        db.status.return_value = {"pending": 0, "running": 0}
        db.locks.return_value = {}
        db.results = {}
        db.is_locked.side_effect = [False, True]
        config = worker.normalize_config({"enabled": True})
        runtime = {"enabled": True, "runs": [], "done": {}, "washed": 0}
        row = {"word": "alpha", "wordId": 1, "bookId": 2, "rating": "down",
               "count": 2, "phonetic": "a", "meaning": "first"}
        runner = Mock()
        with patch.object(worker, "_word_index", {"loaded": True, "by_word": {"alpha": {"word": "alpha"}}}), patch.object(
            worker.store_mod, "_read_json", return_value={"rows": {}, "upcoming_new_word_ids": ["alpha"]}
        ), patch.object(worker, "_feedback_rows", return_value=[row]), patch.object(
            worker, "_load_runtime", return_value=runtime
        ), patch.object(worker, "_save_runtime") as save:
            result = worker.run_once(db, runner=runner, config=config)
        self.assertEqual((result["candidates"], result["reason"], result["washed"]), (1, "nothing_washed", []))
        self.assertEqual(len(db.is_locked.call_args_list), 2)
        self.assertEqual(db.is_locked.call_args_list[0], db.is_locked.call_args_list[1])
        runner.assert_not_called()
        self.assertEqual((runtime["runs"], runtime["done"], runtime["washed"]), ([], {}, 0))
        save.assert_called_once_with(runtime)


if __name__ == "__main__":
    unittest.main()
