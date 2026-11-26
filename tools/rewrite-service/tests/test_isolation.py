"""测试隔离自检：真实 8788 必须被拦截，日志必须落在临时目录。"""
import os
import socket
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import service
from _isolation import guard_network, isolate


isolate(service)
guard_network()


class IsolationGuardTest(unittest.TestCase):
    def test_real_wb_gateway_is_blocked(self):
        with self.assertRaises(ConnectionRefusedError):
            socket.create_connection(("127.0.0.1", 8788), timeout=0.5)

    def test_service_log_never_points_at_repo_data(self):
        self.assertIn("wae-tests-", service.DATA_DIR)
        self.assertIn("wae-tests-", service.LOG_PATH)
        self.assertNotEqual(
            os.path.normcase(service.LOG_PATH),
            os.path.normcase(os.path.join(
                os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                "data", "logs", "service.log")),
        )


if __name__ == "__main__":
    unittest.main()
