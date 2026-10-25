import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "gcp"))
from deploy import Deployment, DeployError, LABELS, PROJECT


class DeploymentFixture(Deployment):
    def __init__(self):
        self.project = PROJECT["project"]
        self.region = PROJECT["region"]
        self.project_number = "123456789012"
        self.billing_account = "billingAccounts/SYNTHETIC-TEST-ONLY"
        self.topic = f"projects/{self.project}/topics/futurecode-cost-events"
        self.service = "futurecode"
        self.guard_service = "futurecode-cost-guard"
        self.state = {"project": self.project}
        self.calls = []
        self.replies = []

    def api(self, method, url, **kwargs):
        self.calls.append((method, url, kwargs))
        return self.replies.pop(0) if self.replies else {}

    def save(self):
        pass


class DeploymentTest(unittest.TestCase):
    def test_project_is_the_dedicated_user_selected_project_and_not_the_old_project(self):
        self.assertEqual(PROJECT["project"], "futurecode-512719")
        self.assertEqual(PROJECT["repository"], "https://github.com/dhruv-15-03/FutureCode.git")

    def test_unapproved_or_old_projects_fail_before_running_cloud_commands(self):
        with patch.dict(os.environ, {"FUTURECODE_APPROVED_GCP_PROJECT": PROJECT["project"]}):
            for project in ("eighth-service-460007-k8", "some-other-project"):
                with self.subTest(project=project), self.assertRaises(DeployError):
                    Deployment(project, "us-central1")

    def test_budget_has_the_exact_five_net_spend_thresholds_and_one_project(self):
        fixture = DeploymentFixture()
        fixture.replies = [{"budgets": []}, {"name": "billingAccounts/fixture/budgets/fixture123"}]
        self.assertEqual(fixture.budget("projects/fixture/notificationChannels/123"), "fixture123")
        method, url, arguments = fixture.calls[-1]
        self.assertEqual(method, "POST")
        budget = arguments["body"]
        self.assertEqual(budget["budgetFilter"]["projects"], ["projects/123456789012"])
        self.assertEqual(budget["budgetFilter"]["creditTypesTreatment"], "INCLUDE_ALL_CREDITS")
        self.assertEqual(budget["amount"]["specifiedAmount"], {"currencyCode": "INR", "units": "5"})
        self.assertEqual([rule["thresholdPercent"] for rule in budget["thresholdRules"]], [.2, .4, .6, .8, 1.0])
        self.assertNotIn("spendCap", budget)
        self.assertEqual(budget["notificationsRule"]["pubsubTopic"], fixture.topic)
        self.assertTrue(budget["notificationsRule"]["disableDefaultIamRecipients"])
        self.assertTrue(budget["notificationsRule"]["enableProjectLevelRecipients"])

    def test_an_existing_budget_in_another_project_cannot_be_repurposed(self):
        fixture = DeploymentFixture()
        fixture.replies = [{"budgets": [{
            "displayName": f"FutureCode {fixture.project} INR 5 net stop",
            "budgetFilter": {"projects": ["projects/999"]}, "etag": "fixture",
            "name": "billingAccounts/fixture/budgets/other"
        }]}]
        with self.assertRaisesRegex(DeployError, "budget_project_scope_mismatch"):
            fixture.budget("projects/fixture/notificationChannels/123")
        self.assertEqual(len(fixture.calls), 1)

    def test_alert_matches_only_this_projects_guard_log_and_has_one_email_channel(self):
        fixture = DeploymentFixture()
        fixture.replies = [{"alertPolicies": []}, {"name": f"projects/{fixture.project}/alertPolicies/123"}]
        fixture.quota_alert("projects/fixture/notificationChannels/123")
        body = fixture.calls[-1][2]["body"]
        self.assertIn(f'projects/{fixture.project}/logs/futurecode-cost-guard', body["conditions"][0]["conditionMatchedLog"]["filter"])
        self.assertEqual(len(body["notificationChannels"]), 1)
        self.assertTrue(body["enabled"])
        self.assertEqual(body["alertStrategy"]["notificationRateLimit"]["period"], "3600s")

    def test_existing_unowned_resources_are_not_overwritten(self):
        with self.assertRaises(DeployError):
            DeploymentFixture().owned({"labels": {"app": "different"}})
        DeploymentFixture().owned({"labels": LABELS})

    def test_application_cannot_be_deployed_before_the_cost_guard(self):
        with self.assertRaisesRegex(DeployError, "cost_protection_required_before_app"):
            DeploymentFixture().application("not-read.env", "not-read.pem")

    def test_only_authenticated_readiness_and_quota_probes_can_be_published(self):
        fixture = DeploymentFixture()
        with self.assertRaises(DeployError):
            fixture.publish_check("fake-billing-shutdown")
        fixture.publish_check("readiness-check")
        self.assertTrue(fixture.calls[-1][1].endswith("futurecode-cost-events:publish"))

    def test_app_startup_probe_waits_for_full_readiness_and_preserves_the_strict_host_boundary(self):
        fixture = DeploymentFixture()
        fixture.app_identity = "futurecode-app@" + fixture.project + ".iam.gserviceaccount.com"
        origin = "https://futurecode-fixture-uc.a.run.app"
        document = fixture.app_service_document("synthetic-image", fixture.app_identity,
            {"APP_ORIGIN": origin}, {"/secrets/config/futurecode.properties": ("futurecode-config", "1")})
        container = document["spec"]["template"]["spec"]["containers"][0]
        self.assertEqual(container["startupProbe"]["httpGet"], {
            "path": "/readyz", "port": 8080,
            "httpHeaders": [{"name": "Host", "value": "futurecode-fixture-uc.a.run.app"}]
        })
        self.assertLessEqual(container["startupProbe"]["periodSeconds"] * container["startupProbe"]["failureThreshold"], 240)
        self.assertEqual(container["resources"]["limits"], {"cpu": "1", "memory": "512Mi"})


if __name__ == "__main__":
    unittest.main()
