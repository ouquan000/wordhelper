#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""_write_json 在多工人并发下的瞬时占用重试。"""

import os
import tempfile
import unittest
from unittest import mock

import store


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


if __name__ == "__main__":
    unittest.main()
