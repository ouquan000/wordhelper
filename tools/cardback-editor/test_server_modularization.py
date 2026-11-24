"""入口、重写挂载与 facade 兼容契约。所有请求在内存内分派。"""

import ast
import importlib.util
import io
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import Mock, patch


HERE = Path(__file__).resolve().parent
MODULES = sorted(HERE.glob("cardback_*.py"))
FAKE_SERVICE = """\
CAPABILITIES = ("rewrite_scoped",)
calls = []
started = 0

def startup():
    global started
    started += 1

def respond(method, path, body):
    calls.append((method, path, body))
    if path == "/rewrite/missing":
        return None
    if path == "/rewrite/error":
        raise ValueError("fixture")
    return 207, "application/example", b"reply"
"""


class ServerContracts(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.editor = self.root / "tools" / "cardback-editor"
        self.editor.mkdir(parents=True)
        for source in [HERE / "server.py", HERE / "tls_support.py", *MODULES]:
            shutil.copyfile(source, self.editor / source.name)
        self.entry = self.editor / "server.py"
        self.addCleanup(sys.path.__setitem__, slice(None), list(sys.path))
        self.modules = patch.dict(sys.modules)
        self.modules.start()
        self.addCleanup(self.modules.stop)
        for name in ["service", "server", "tls_support", *(f.stem for f in MODULES)]:
            sys.modules.pop(name, None)

    def load(self):
        spec = importlib.util.spec_from_file_location("standalone_editor", self.entry)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def install_service(self, layout):
        directory = layout / "rewrite-service"
        directory.mkdir()
        (directory / "service.py").write_text(FAKE_SERVICE, encoding="utf-8")
        return directory

    def command(self, cwd, *args):
        return subprocess.run(
            [sys.executable, "-B", "-X", "utf8", *args],
            cwd=cwd, capture_output=True, encoding="utf-8", timeout=15,
        )

    def test_script_from_repository_root(self):
        result = self.command(self.root, "tools/cardback-editor/server.py", "--port=invalid")
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("端口不对", result.stdout)
        self.assertFalse((self.editor / "published").exists())

    def test_import_from_editor(self):
        code = "import server; assert server.Handler and server.tls_support and server.PORT == 8765"
        result = self.command(self.editor, "-c", code)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_spec_from_repository_root(self):
        code = (
            "import importlib.util as u; "
            "s=u.spec_from_file_location('independent', 'tools/cardback-editor/server.py'); "
            "m=u.module_from_spec(s); s.loader.exec_module(m); "
            "assert m.Handler and m.tls_support and m.BASE_DIR.endswith('cardback-editor')"
        )
        result = self.command(self.root, "-c", code)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_modules_have_no_import_cycles(self):
        paths = {p.stem: p for p in [self.entry, *self.editor.glob("cardback_*.py")]}
        graph = {}
        for name, path in paths.items():
            imports = set()
            for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
                if isinstance(node, ast.Import):
                    imports.update(item.name for item in node.names)
                elif isinstance(node, ast.ImportFrom):
                    imports.add(node.module)
            graph[name] = imports.intersection(paths)
        self.assertEqual(graph["server"], set(paths) - {"server"})

        def visit(name, stack):
            self.assertNotIn(name, stack)
            for dep in graph[name]:
                visit(dep, stack | {name})

        for name in graph:
            visit(name, set())

    def test_source_sidecar_and_cache(self):
        sibling = self.install_service(self.editor.parent)
        editor = self.load()
        service = editor._rewrite_module()
        self.assertEqual(Path(service.__file__).parent, sibling)
        self.install_service(self.editor)
        self.assertIs(editor._rewrite_module(), service)

    def test_adjacent_sidecar_wins(self):
        adjacent = self.install_service(self.editor)
        self.install_service(self.editor.parent)
        self.assertEqual(Path(self.load()._rewrite_module().__file__).parent, adjacent)

    def test_frozen_paths_and_startup(self):
        executable = self.root / "release" / "Editor.exe"
        executable.parent.mkdir()
        sidecar = self.install_service(executable.parent)
        with patch.object(sys, "frozen", True, create=True), \
                patch.object(sys, "_MEIPASS", str(self.editor), create=True), \
                patch.object(sys, "executable", str(executable)):
            editor = self.load()
        self.assertEqual(Path(editor.BASE_DIR), executable.parent)
        self.assertEqual(Path(editor.INDEX_PATH), self.editor / "index.html")
        service = editor._rewrite_module()
        self.assertEqual(Path(service.__file__).parent, sidecar)
        self.assertIn("rewrite_scoped", editor._health_capabilities())
        self.assertEqual(service.started, 0)
        with redirect_stdout(io.StringIO()):
            editor._start_rewrite()
        self.assertEqual(service.started, 1)

    def test_routes_forward_original_request_and_response(self):
        self.install_service(self.editor)
        editor = self.load()
        handler = object.__new__(editor.Handler)
        handler._send_bytes = Mock()
        for verb, path, body in [("GET", "/models?x=1", b""), ("GET", "/rewrite/status", b""),
                                 ("POST", "/rewrite/marks?seq=2", b"\x00data")]:
            handler.path = path
            if verb == "GET":
                handler._route_get(path.split("?")[0])
            else:
                handler._route_post(path.split("?")[0], body)
            self.assertEqual(editor._rewrite_module().calls[-1], (verb, path, body))
            handler._send_bytes.assert_called_with(b"reply", "application/example", status=207)

    def test_rewrite_errors_and_unsupported_methods(self):
        self.install_service(self.editor.parent)
        editor = self.load()
        handler = object.__new__(editor.Handler)
        handler._fail = Mock()
        for path, status, message in [("/rewrite/missing", 404, "没有这个路径：/rewrite/missing"),
                                      ("/rewrite/error", 500, "重写服务出错：fixture")]:
            handler.path = path
            handler._route_get(path)
            handler._fail.assert_called_with(status, message)
        handler._route_post("/models", b"")
        handler._fail.assert_called_with(404, "没有这个路径：/models")
        for method in ("PUT", "DELETE", "OPTIONS"):
            self.assertFalse(hasattr(handler, "do_" + method))

    def test_missing_sidecar_keeps_health_available(self):
        editor = self.load()
        with self.assertRaisesRegex(RuntimeError, "找不到"):
            editor._rewrite_module()
        self.assertEqual(editor._health_capabilities(), list(editor.CAPABILITIES))

    def test_facade_hooks_and_state_are_live_per_entry(self):
        editor = self.load()
        independent = self.load()
        editor.PUBLISHED_DIR = str(self.root / "other")
        self.assertEqual(Path(editor.cards_dir()), self.root / "other" / "cards")
        self.assertNotEqual(independent.cards_dir(), editor.cards_dir())
        editor.INSTANCE_PATH = str(self.root / "identity")
        identity = editor.ensure_instance_id()
        self.assertEqual(identity, editor.INSTANCE_ID)
        self.assertEqual(independent.INSTANCE_ID, "")
        handler = object.__new__(editor.Handler)
        handler._send_json = Mock()
        editor.PORT = 19876
        with patch.object(editor, "_rewrite_module") as lookup:
            lookup.return_value.CAPABILITIES = ("rewrite_scoped",)
            handler._api_health()
            lookup.assert_called_once_with()
            lookup.return_value.startup.assert_not_called()
        payload = handler._send_json.call_args.args[0]
        self.assertEqual(payload["port"], 19876)
        self.assertEqual(payload["instance_id"], identity)
        self.assertIn("rewrite_scoped", payload["capabilities"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
