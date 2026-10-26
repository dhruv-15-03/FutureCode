import os
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import Mock, patch
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "gcp"))
from deploy import Deployment, DeployError, LABELS, PROJECT, main


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
        self.repository = "futurecode"
        self.bucket = "fixture-source-bucket"
        self.build_identity = "fixture-build@example.invalid"
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
        with patch.dict(PROJECT, {"cloud_changes_enabled": True}):
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

    def test_cloud_mutation_hold_is_enabled_by_default(self):
        self.assertIs(PROJECT["cloud_changes_enabled"], False)
        fixture = DeploymentFixture()
        fixture.session = Mock()
        for method in ("POST", "PUT", "PATCH", "DELETE"):
            with self.subTest(method=method), self.assertRaisesRegex(DeployError, "cloud_changes_blocked"):
                Deployment.api(fixture, method, "https://run.googleapis.com/v2/anywhere")
        fixture.session.request.assert_not_called()

    def test_cli_mutations_are_blocked_before_starting_any_process(self):
        fixture = DeploymentFixture()
        for command in [
            ("builds", "submit"), ("run", "deploy", "futurecode"),
            ("billing", "projects", "link", fixture.project),
            ("services", "enable", "run.googleapis.com"),
            ("storage", "rm", "gs://example"), ("unknown", "operation")
        ]:
            with self.subTest(command=command), patch("deploy.subprocess.run") as process:
                with self.assertRaisesRegex(DeployError, "cloud_changes_blocked"):
                    Deployment.gcloud(fixture, *command)
                process.assert_not_called()

    def test_entry_points_are_blocked_even_with_the_former_risk_acceptance_flag(self):
        for phase in ("protect", "app", "verify"):
            args = ["deploy.py", "--project=" + PROJECT["project"], "--phase=" + phase, "--accept-estimated-target-risk"]
            with self.subTest(phase=phase), patch.object(sys, "argv", args), patch("deploy.Deployment") as factory:
                with self.assertRaisesRegex(DeployError, "cloud_changes_blocked"):
                    main()
                factory.assert_not_called()

    def test_high_level_write_paths_are_blocked_before_reads_or_private_configuration(self):
        fixture = DeploymentFixture()
        fixture.state = {"guardUrl": "synthetic", "budget": "synthetic", "protectionVerified": "synthetic"}
        for work in (
            lambda: fixture.source_image("app"), lambda: fixture.protect(),
            lambda: fixture.service_deploy("futurecode", "image", "identity", {}),
            lambda: fixture.application("must-not-read.env", "must-not-read.pem"),
            lambda: fixture.secret("must-not-create"), lambda: fixture.publish_check("readiness-check"),
            lambda: fixture.verify_protection()
        ):
            with self.assertRaisesRegex(DeployError, "cloud_changes_blocked"):
                work()
        self.assertEqual(fixture.calls, [])

    def test_free_promotion_build_type_and_default_pool_are_explicit(self):
        fixture = DeploymentFixture()
        fixture.bucket = "fixture-source-bucket"
        fixture.build_identity = "fixture@example.invalid"
        for target in ("app", "cost-guard"):
            body = fixture.build_request(target, "fixture-commit", "fixture-image", "fixture-source.tar.gz")
            self.assertEqual(body["options"]["machineType"], "E2_STANDARD_2")
            self.assertNotIn("pool", body["options"])
            self.assertNotIn("diskSizeGb", body["options"])

    def test_registry_checks_use_real_repository_gauge_not_compressed_image_sizes(self):
        fixture = DeploymentFixture()
        now = datetime(2026, 10, 26, 3, tzinfo=timezone.utc)
        fixture.replies = [{"timeSeries": [{"metricKind": "GAUGE", "points": [{
            "interval": {"endTime": now.isoformat()}, "value": {"int64Value": str(512 * 1024 * 1024 - 1)}
        }]}]}]
        result = fixture.registry_footprint(now)
        self.assertEqual(result["reportedBytes"], 512 * 1024 * 1024 - 1)
        self.assertFalse(result["accountWideFreeBalanceVerified"])
        query = fixture.calls[0][2]["params"]["filter"]
        self.assertIn("artifactregistry.googleapis.com/repository/size", query)
        self.assertNotIn("imageSizeBytes", query)

    def test_registry_at_the_free_baseline_or_above_is_rejected(self):
        fixture = DeploymentFixture()
        now = datetime(2026, 10, 26, 3, tzinfo=timezone.utc)
        for size in (512 * 1024 * 1024, 1027478392):
            fixture.replies = [{"timeSeries": [{"metricKind": "GAUGE", "points": [{
                "interval": {"endTime": now.isoformat()}, "value": {"int64Value": str(size)}
            }]}]}]
            with self.subTest(size=size), self.assertRaisesRegex(DeployError, "at_or_above_free_baseline"):
                fixture.registry_footprint(now)

    def test_registry_missing_paginated_stale_or_invalid_data_is_not_reported_as_zero(self):
        now = datetime(2026, 10, 26, 3, tzinfo=timezone.utc)
        point = lambda at, size: {"interval": {"endTime": at}, "value": {"int64Value": size}}
        cases = [
            {}, {"timeSeries": []}, {"timeSeries": [], "nextPageToken": "more"},
            {"timeSeries": [{"metricKind": "GAUGE", "points": []}]},
            {"timeSeries": [{"metricKind": "GAUGE", "points": [point((now - timedelta(hours=2)).isoformat(), "100")]}]},
            {"timeSeries": [{"metricKind": "GAUGE", "points": [point(now.isoformat(), "-1")]}]},
            {"timeSeries": [{"metricKind": "DELTA", "points": [point(now.isoformat(), "100")]}]}
        ]
        for value in cases:
            fixture = DeploymentFixture()
            fixture.replies = [value]
            with self.subTest(value=value), self.assertRaises(DeployError):
                fixture.registry_footprint(now)

    def test_stale_legacy_proof_cannot_authorize_building(self):
        fixture = DeploymentFixture()
        fixture.state.update({"guardUrl": "https://fixture.run.app", "budget": "fixture-budget",
                              "protectionVerified": "2000-01-01T00:00:00Z"})
        with self.assertRaisesRegex(DeployError, "fresh_project_bound_protection_required"):
            fixture.require_fresh_protection()
        fixture.require_cost_review = Mock(return_value="fixture-review")
        fixture.current_commit = Mock(return_value="a" * 40)
        fixture.require_release_verification = Mock()
        fixture.verify_protection = Mock(side_effect=DeployError("fixture_live_check_required"))
        fixture.source_image = Mock()
        with patch.dict(PROJECT, {"cloud_changes_enabled": True}), self.assertRaisesRegex(DeployError, "fixture_live_check_required"):
            fixture.application("not-read.env", "not-read.pem")
        fixture.verify_protection.assert_called_once()
        fixture.source_image.assert_not_called()

    def test_protection_proof_is_short_lived_and_bound_to_project_budget_and_guard(self):
        fixture = DeploymentFixture()
        now = datetime(2026, 10, 26, 6, tzinfo=timezone.utc)
        fixture.state.update({"guardUrl": "https://fixture.run.app", "budget": "fixture-budget"})
        proof = {
            "project": fixture.project, "billingAccount": fixture.billing_account,
            "budget": "fixture-budget", "guardUrl": "https://fixture.run.app",
            "guardRevision": "futurecode-cost-guard-00001-abc", "probeId": "a" * 32,
            "verifiedAt": now.isoformat()
        }
        fixture.state["protectionAttestation"] = proof.copy()
        fixture.require_fresh_protection(now)
        for key, value in (("project", "other"), ("billingAccount", "other"), ("guardUrl", "https://other.run.app"),
                           ("budget", "other"), ("probeId", "not-a-probe"),
                           ("verifiedAt", (now - timedelta(seconds=301)).isoformat())):
            fixture.state["protectionAttestation"] = {**proof, key: value}
            with self.subTest(key=key), self.assertRaises(DeployError):
                fixture.require_fresh_protection(now)

    def test_bootstrap_reviews_precede_writes_and_budget_precedes_build_infrastructure(self):
        fixture = DeploymentFixture()
        order = []
        fixture.require_cost_review = lambda _: order.append("review")
        fixture.current_commit = Mock(return_value="a" * 40)
        fixture.require_release_verification = lambda _: order.append("ci")
        fixture.registry_footprint = lambda: order.append("storage_check")
        fixture.budget_foundation = lambda: order.append("budget_foundation") or "channel"
        fixture.budget = lambda _: order.append("budget") or "budget"
        fixture.quota_alert = lambda _: order.append("alert") or "alert"
        fixture.infrastructure = lambda: order.append("build_infrastructure")
        fixture.source_image = Mock(side_effect=DeployError("fixture_stop_before_build"))
        with patch.dict(PROJECT, {"cloud_changes_enabled": True}), self.assertRaisesRegex(DeployError, "fixture_stop"):
            fixture.protect()
        self.assertEqual(order, ["review", "ci", "storage_check", "budget_foundation", "budget", "alert", "build_infrastructure"])

    def test_unverified_cost_review_blocks_before_any_infrastructure_write(self):
        fixture = DeploymentFixture()
        fixture.infrastructure = Mock()
        fixture.budget_foundation = Mock()
        with patch.dict(PROJECT, {"cloud_changes_enabled": True}), self.assertRaisesRegex(DeployError, "cost_review_required"):
            fixture.protect()
        fixture.infrastructure.assert_not_called()
        fixture.budget_foundation.assert_not_called()

    def test_documentation_commit_reuses_verified_image_when_runtime_inputs_match(self):
        fixture = DeploymentFixture()
        image = f"us-central1-docker.pkg.dev/{fixture.project}/futurecode/app@sha256:" + "a" * 64
        fixture.state["images"] = {"app": {"image": image, "commit": "b" * 40, "buildInputsSha256": "unchanged-inputs"}}
        fixture.require_cost_review = Mock()
        fixture.require_fresh_protection = Mock()
        fixture.registry_footprint = Mock(return_value={"reportedBytes": 100})
        fixture.build_input_fingerprint = Mock(return_value="unchanged-inputs")
        fixture.require_release_verification = Mock()
        replies = [SimpleNamespace(stdout=b""), SimpleNamespace(stdout=("c" * 40).encode())]
        with patch.dict(PROJECT, {"cloud_changes_enabled": True}), patch("deploy.subprocess.run", side_effect=replies) as process:
            self.assertEqual(fixture.source_image("app"), image)
        self.assertEqual(process.call_count, 2)
        self.assertEqual(fixture.calls, [])

    def test_build_fingerprint_excludes_documentation_and_tracks_runtime_files(self):
        fixture = DeploymentFixture()
        with patch("deploy.subprocess.run", return_value=SimpleNamespace(stdout=b"fixture-tree")) as process:
            self.assertEqual(fixture.build_input_fingerprint("app"), fixture.build_input_fingerprint("app", "a" * 40))
        paths = process.call_args.args[0]
        self.assertIn("src/main", paths)
        self.assertIn("src/test", paths)
        self.assertNotIn("README.md", paths)

    def test_unresolved_build_is_not_automatically_submitted_again(self):
        fixture = DeploymentFixture()
        fixture.state["activeBuild"] = "synthetic-unresolved-build"
        fixture.require_cost_review = Mock()
        fixture.require_fresh_protection = Mock()
        fixture.registry_footprint = Mock(return_value={"reportedBytes": 100})
        fixture.build_input_fingerprint = Mock(return_value="inputs")
        fixture.require_release_verification = Mock()
        replies = [SimpleNamespace(stdout=b""), SimpleNamespace(stdout=("c" * 40).encode())]
        with patch.dict(PROJECT, {"cloud_changes_enabled": True}), patch("deploy.subprocess.run", side_effect=replies):
            with self.assertRaisesRegex(DeployError, "unresolved_cloud_build"):
                fixture.source_image("app")
        self.assertEqual(fixture.calls, [])

    def test_post_build_storage_requires_a_source_sample_after_the_build_finished(self):
        fixture = DeploymentFixture()
        now = datetime(2026, 10, 26, 6, tzinfo=timezone.utc)
        fixture.replies = [{"timeSeries": [{"metricKind": "GAUGE", "points": [{
            "interval": {"endTime": (now - timedelta(seconds=30)).isoformat()}, "value": {"int64Value": "100"}
        }]}]}]
        with self.assertRaisesRegex(DeployError, "post_build_registry_measurement"):
            fixture.registry_footprint(now, not_before=now - timedelta(seconds=10))

    def test_live_protection_proof_is_correlated_with_a_unique_probe(self):
        fixture = DeploymentFixture()
        fixture.guard_identity = "fixture-guard@example.invalid"
        fixture.push_identity = "fixture-push@example.invalid"
        fixture.subscription = f"projects/{fixture.project}/subscriptions/futurecode-cost-guard"
        fixture.state.update({"budget": "billingAccounts/fixture/budgets/fixture123",
                              "guardUrl": "https://fixture.run.app", "notificationChannel": "fixture-channel",
                              "protectionVerified": "2000-01-01T00:00:00Z"})
        fixture.require_cost_review = Mock()
        fixture.publish_check = Mock()
        fixture.session = Mock()
        fixture.session.get.return_value.status_code = 403
        fixture.verify_service_profile = Mock()
        fixture.replies = [
            {
                "budgetFilter": {"projects": ["projects/" + fixture.project_number],
                                 "creditTypesTreatment": "INCLUDE_ALL_CREDITS", "calendarPeriod": "MONTH"},
                "amount": {"specifiedAmount": {"currencyCode": "INR", "units": "5"}},
                "thresholdRules": [{"thresholdPercent": value} for value in (.2, .4, .6, .8, 1.)],
                "notificationsRule": {"pubsubTopic": fixture.topic, "monitoringNotificationChannels": ["fixture-channel"]}
            },
            {"topic": fixture.topic, "pushConfig": {
                "pushEndpoint": "https://fixture.run.app",
                "oidcToken": {"serviceAccountEmail": fixture.push_identity, "audience": "https://fixture.run.app"}
            }}
        ]
        def cloud(*arguments, **kwargs):
            if arguments[0] == "pubsub":
                return {"bindings": [{"role": "roles/pubsub.publisher",
                                      "members": ["serviceAccount:billing-budget-alert@system.gserviceaccount.com"]}]}
            if arguments[:3] == ("run", "services", "describe"):
                return {"status": {"latestReadyRevisionName": "futurecode-cost-guard-00001-abc"}}
            if arguments[0] == "run":
                return {"bindings": []}
            self.assertIn('jsonPayload.probeId="' + "d" * 32 + '"', arguments[2])
            return [{"jsonPayload": {"action": "permissions_verified", "probeId": "d" * 32}},
                    {"jsonPayload": {"action": "quota_checked", "probeId": "d" * 32}}]
        fixture.gcloud = cloud
        with patch.dict(PROJECT, {"cloud_changes_enabled": True}), patch("deploy.uuid.uuid4", return_value=SimpleNamespace(hex="d" * 32)):
            fixture.verify_protection()
        self.assertEqual(fixture.state["protectionAttestation"]["probeId"], "d" * 32)
        self.assertEqual(fixture.state["protectionAttestation"]["project"], fixture.project)
        fixture.require_fresh_protection()
        self.assertEqual(fixture.publish_check.call_args_list[0].args, ("readiness-check", "d" * 32))

    def test_release_ci_must_belong_to_this_repository_workflow_and_exact_commit(self):
        fixture = DeploymentFixture()
        commit = "a" * 40
        valid = {"head_sha": commit, "head_branch": "main", "event": "push", "status": "completed",
                 "conclusion": "success", "path": ".github/workflows/verify.yml",
                 "head_repository": {"full_name": "dhruv-15-03/FutureCode"}}
        response = Mock(status_code=200)
        response.json.return_value = {"workflow_runs": [valid]}
        with patch("deploy.requests.get", return_value=response) as request:
            fixture.require_release_verification(commit)
            self.assertNotIn("Authorization", request.call_args.kwargs["headers"])
        for changed in ({"head_sha": "b" * 40}, {"conclusion": "failure"}, {"head_branch": "other"},
                        {"path": ".github/workflows/deploy.yml"}, {"head_repository": {"full_name": "other/repo"}}):
            response.json.return_value = {"workflow_runs": [{**valid, **changed}]}
            with self.subTest(changed=changed), patch("deploy.requests.get", return_value=response):
                with self.assertRaisesRegex(DeployError, "successful_release_ci_required"):
                    fixture.require_release_verification(commit)


if __name__ == "__main__":
    unittest.main()
