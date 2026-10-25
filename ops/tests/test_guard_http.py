import base64
import json
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

from flask import Flask, request

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "cost_guard"))
import main


class GuardHttpTest(unittest.TestCase):
    def setUp(self):
        self.app = Flask(__name__)

    def test_wrong_method_and_non_json_fail_before_cloud_credentials_are_loaded(self):
        for method, content_type in (("GET", None), ("POST", "text/plain")):
            with self.app.test_request_context("/", method=method, content_type=content_type, data="{}"):
                with patch.object(main, "GcpBackend") as backend:
                    body, code = main.protect_futurecode.__wrapped__(request)
                    self.assertEqual(code, 500)
                    self.assertEqual(body["code"], "json_post_required")
                    backend.assert_not_called()

    def test_oversized_and_malformed_json_do_not_call_cloud_apis(self):
        for raw, error in ((" " * 32769, "event_too_large"), ("{", "invalid_push_body")):
            with self.app.test_request_context("/", method="POST", content_type="application/json", data=raw):
                with patch.object(main, "GcpBackend") as backend:
                    body, code = main.protect_futurecode.__wrapped__(request)
                    self.assertEqual(code, 500)
                    self.assertEqual(body["code"], error)
                    backend.assert_not_called()

    def test_valid_authenticated_subscription_envelope_performs_readiness_only(self):
        project = "futurecode-test-project"
        value = {
            "subscription": f"projects/{project}/subscriptions/futurecode-cost-guard",
            "message": {
                "messageId": "fixture",
                "attributes": {"futurecodeEvent": "readiness-check"},
                "data": base64.b64encode(b'{"kind":"readiness-check"}').decode()
            }
        }
        environment = {
            "TARGET_PROJECT": project, "EXPECTED_BUDGET_ID": "fixture-budget-123",
            "STATE_BUCKET": "fixture-state-bucket", "APP_SERVICE_NAME": "futurecode",
            "QUOTA_ALERT_POLICY": f"projects/{project}/alertPolicies/123"
        }
        with self.app.test_request_context("/", method="POST", content_type="application/json", data=json.dumps(value)):
            with patch.dict(os.environ, environment), patch.object(main, "GcpBackend") as backend:
                body, code = main.protect_futurecode.__wrapped__(request)
                self.assertEqual(code, 204)
                backend.return_value.verify_permissions.assert_called_once()
                backend.return_value.disable_billing.assert_not_called()


if __name__ == "__main__":
    unittest.main()
