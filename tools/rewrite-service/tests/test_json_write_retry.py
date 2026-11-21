#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""_write_json 在多工人并发下的瞬时占用重试（store 与 wb_gate 同一套口径）。"""

import os
import tempfile
import threading
import unittest
from unittest import mock

import store
import wb_gate


class JsonWriteRetryTest(unittest.TestCase):
    def test_retries_transient_permission_error(self):
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "queue.json")
            real_replace = os.replace
            calls = {"n": 0}

            def flaky_replace(src, dst):
                calls["n"] += 1
                if calls["n"] <= 2:
                    raise PermissionError("scanning")
                return real_replace(src, dst)

            with mock.patch.object(store.os, "replace", side_effect=flaky_replace):
                store._write_json(path, {"ok": True})

            self.assertEqual(calls["n"], 3)
            self.assertTrue(os.path.exists(path))
            self.assertFalse(os.path.exists(path + ".tmp"))

    def test_cleans_temp_file_after_final_failure(self):
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "queue.json")
            with mock.patch.object(store.os, "replace", side_effect=PermissionError("locked")):
                with self.assertRaises(PermissionError):
                    store._write_json(path, {"ok": True})
            self.assertFalse(os.path.exists(path))
            leftovers = [name for name in os.listdir(root) if name.endswith(".tmp")]
            self.assertEqual(leftovers, [])


class WbGateJsonWriteRetryTest(unittest.TestCase):
    def test_retries_transient_permission_error_with_pid_and_thread_tmp(self):
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "wb_gate.json")
            expected_tmp = "%s.%d.%d.tmp" % (path, os.getpid(), threading.get_ident())
            real_replace = os.replace
            sources = []

            def flaky_replace(src, dst):
                sources.append(src)
                if len(sources) <= 2:
                    raise PermissionError("scanning")
                return real_replace(src, dst)

            with mock.patch.object(wb_gate.os, "replace", side_effect=flaky_replace), \
                 mock.patch.object(wb_gate.time, "sleep") as sleep:
                wb_gate._write_json(path, {"ok": True})

            self.assertEqual(len(sources), 3)
            self.assertEqual(sources[0], expected_tmp)
            self.assertEqual([call.args[0] for call in sleep.call_args_list], [0.02, 0.04])
            self.assertTrue(os.path.exists(path))
            self.assertEqual(sorted(os.listdir(root)), ["wb_gate.json"])

    def test_cleans_temp_file_after_final_failure(self):
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "wb_gate.json")
            with mock.patch.object(wb_gate.os, "replace", side_effect=PermissionError("locked")), \
                 mock.patch.object(wb_gate.time, "sleep"):
                with self.assertRaises(PermissionError):
                    wb_gate._write_json(path, {"ok": True})
            self.assertFalse(os.path.exists(path))
            self.assertEqual([name for name in os.listdir(root) if name.endswith(".tmp")], [])


if __name__ == "__main__":
    unittest.main()
