import base64
import io
import json
import sys
import unittest
from contextlib import redirect_stderr
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "cost_guard"))
from guard import Config, GuardError, MeterReading, POLICY, month_start, protect


CONFIG = Config("futurecode-test-project", "budget-test-1234", "futurecode-test-state",
                "futurecode", "projects/futurecode-test-project/alertPolicies/123")
NOW = datetime(2026, 10, 25, 12, tzinfo=timezone.utc)


def event(body, attributes=None):
    return {
        "subscription": "projects/futurecode-test-project/subscriptions/futurecode-cost-guard",
        "message": {
            "messageId": "synthetic-test-only",
            "data": base64.b64encode(json.dumps(body).encode()).decode(),
            "attributes": attributes or {"budgetId": CONFIG.budget_id, "schemaVersion": "1.0"}
        }
    }


def budget(cost, **extra):
    return event({"costAmount": cost, "budgetAmount": 5, "currencyCode": "INR",
                  "costIntervalStart": month_start(NOW).isoformat(), **extra})


def quota():
    return event({"kind": "quota-check"}, {"futurecodeEvent": "quota-check"})


class Backend:
    def __init__(self):
        self.disabled = []
        self.emitted = []
        self.state = {}
        self.saved = []
        self.reads = []
        self.meters = {}
        self.log_fails = False
        self.permissions_verified = False
        self.policy_disabled = 0

    def emit(self, name, details, insert_id):
        if self.log_fails:
            raise GuardError("synthetic_logging_failure")
        self.emitted.append((name, details, insert_id))

    def disable_billing(self, project):
        self.disabled.append(project)
        return True

    def verify_permissions(self):
        self.permissions_verified = True

    def load_state(self, key):
        return dict(self.state), "7"

    def save_state(self, key, state, generation):
        self.saved.append((key, dict(state), generation))
        self.state = dict(state)

    def read_meter(self, name, start, end):
        self.reads.append((name, start, end))
        return MeterReading(Decimal(str(self.meters.get(name, 0))), end)

    def disable_quota_alert(self):
        self.policy_disabled += 1


class CostGuardTest(unittest.TestCase):
    def test_all_five_requested_spend_alerts_and_three_free_allowances_are_explicit(self):
        self.assertEqual(POLICY["spend_alerts"], ["1", "2", "3", "4", "5"])
        self.assertEqual(POLICY["currency"], "INR")
        self.assertEqual([m["monthly_free"] for m in POLICY["meters"]], ["2000000", "180000", "360000"])

    def test_exact_five_rupees_stops_only_the_fixed_project(self):
        for value in ("5", "5.00", "5.0001", "1000"):
            with self.subTest(value=value):
                backend = Backend()
                self.assertEqual(protect(budget(value), CONFIG, backend, NOW)["action"], "project_billing_disabled")
                self.assertEqual(backend.disabled, [CONFIG.project])

    def test_one_through_four_and_last_paisa_before_five_do_not_stop(self):
        for value in ("-1", "0", "1", "2", "3", "4", "4.99"):
            with self.subTest(value=value):
                backend = Backend()
                self.assertEqual(protect(budget(value), CONFIG, backend, NOW)["action"], "below_target")
                self.assertEqual(backend.disabled, [])

    def test_budget_observation_records_amount_currency_period_and_notification_freshness(self):
        backend = Backend()
        value = budget("1.67")
        value["message"]["publishTime"] = NOW.isoformat()
        protect(value, CONFIG, backend, NOW)
        name, details, identifier = backend.emitted[0]
        self.assertEqual(name, "futurecode.cost_observation")
        self.assertEqual(details["amount"], "1.67")
        self.assertEqual(details["currency"], "INR")
        self.assertEqual(details["periodStart"], month_start(NOW).isoformat())
        self.assertEqual(details["notificationPublishedAt"], NOW.isoformat())
        self.assertEqual(details["receivedAt"], NOW.isoformat())
        self.assertNotIn("key", details)
        self.assertTrue(identifier.startswith("cost-"))

    def test_missing_publish_time_is_unknown_not_fabricated(self):
        backend = Backend()
        protect(budget("1"), CONFIG, backend, NOW)
        self.assertIsNone(backend.emitted[0][1]["notificationPublishedAt"])

    def test_invalid_publish_time_and_missing_or_stale_telemetry_are_errors(self):
        with self.assertRaises(GuardError):
            protect({**budget(1), "message": {**budget(1)["message"], "publishTime": "invalid"}}, CONFIG, Backend(), NOW)
        for result in (None, Decimal(0), MeterReading(Decimal(0), NOW.replace(year=2000))):
            backend = Backend()
            backend.read_meter = lambda *args: result
            with self.subTest(result=result), self.assertRaises(GuardError):
                protect(quota(), CONFIG, backend, NOW)
            self.assertFalse(backend.saved)

    def test_cost_logging_failure_below_target_is_not_a_successful_observation(self):
        backend = Backend()
        backend.log_fails = True
        with self.assertRaisesRegex(GuardError, "cost_observation_failed"):
            protect(budget("1.67"), CONFIG, backend, NOW)
        self.assertFalse(backend.disabled)

    def test_mismatched_budget_subscription_currency_and_amount_fail_without_mutation(self):
        cases = [
            event({"costAmount": 10}, {"budgetId": "other-budget", "schemaVersion": "1.0"}),
            budget(10, currencyCode="USD"), budget(10, budgetAmount=10)
        ]
        wrong_project = budget(10)
        wrong_project["subscription"] = "projects/other-project/subscriptions/other"
        cases.append(wrong_project)
        for value in cases:
            with self.subTest(value=value):
                backend = Backend()
                with self.assertRaises(GuardError):
                    protect(value, CONFIG, backend, NOW)
                self.assertFalse(backend.disabled)

    def test_nonfinite_missing_and_boolean_amounts_are_not_treated_as_zero(self):
        for value in ("NaN", "Infinity", True, None, {}):
            with self.subTest(value=value):
                with self.assertRaises(GuardError):
                    protect(budget(value), CONFIG, Backend(), NOW)

    def test_previous_month_notification_cannot_shut_down_the_new_month(self):
        backend = Backend()
        old = budget(10, costIntervalStart="2026-09-01T07:00:00Z")
        self.assertEqual(protect(old, CONFIG, backend, NOW)["action"], "ignored_previous_period")
        self.assertFalse(backend.disabled)

    def test_missing_or_future_period_is_an_explicit_failure(self):
        for value in (None, "", "2026-10-02T07:00:00Z"):
            with self.assertRaises(GuardError):
                protect(budget(10, costIntervalStart=value), CONFIG, Backend(), NOW)

    def test_logging_failure_does_not_prevent_the_emergency_stop(self):
        backend = Backend()
        backend.log_fails = True
        with redirect_stderr(io.StringIO()) as captured:
            protect(budget(5), CONFIG, backend, NOW)
        self.assertEqual(backend.disabled, [CONFIG.project])
        self.assertIn("stop_log_failed", captured.getvalue())

    def test_quota_boundary_is_exact_and_billed_memory_uses_the_512_mib_profile(self):
        backend = Backend()
        backend.meters["run.googleapis.com/container/billable_instance_time"] = Decimal("89999.99")
        self.assertEqual(protect(quota(), CONFIG, backend, NOW)["newlyCrossed"], [])
        backend.meters["run.googleapis.com/container/billable_instance_time"] = Decimal("90000")
        self.assertEqual(protect(quota(), CONFIG, backend, NOW)["newlyCrossed"], ["cpu_seconds"])
        half = next(data for name, data, _ in backend.emitted if name == "futurecode.quota_half")
        self.assertEqual(half["usage"]["memory_gib_seconds"]["used"], "45000")
        self.assertEqual(len(backend.reads), 4)

    def test_request_boundary_and_deduplication_are_month_scoped(self):
        backend = Backend()
        backend.meters["run.googleapis.com/request_count"] = 999999
        self.assertEqual(protect(quota(), CONFIG, backend, NOW)["newlyCrossed"], [])
        backend.meters["run.googleapis.com/request_count"] = 1000000
        self.assertEqual(protect(quota(), CONFIG, backend, NOW)["newlyCrossed"], ["requests"])
        self.assertEqual(protect(quota(), CONFIG, backend, NOW)["newlyCrossed"], [])
        self.assertTrue(all(row[0] == "2026-10" and row[2] == "7" for row in backend.saved))

    def test_unknown_or_duplicate_saved_meters_are_rejected(self):
        for state in ({"crossed": ["unknown"]}, {"crossed": ["requests", "requests"]}, {"private": "unexpected"}):
            backend = Backend()
            backend.state = state
            with self.assertRaises(GuardError):
                protect(quota(), CONFIG, backend, NOW)

    def test_negative_or_unavailable_metrics_do_not_fabricate_free_usage(self):
        backend = Backend()
        backend.meters["run.googleapis.com/request_count"] = -1
        with self.assertRaises(GuardError):
            protect(quota(), CONFIG, backend, NOW)
        self.assertFalse(backend.saved)

    def test_readiness_probes_permissions_without_disabling_billing(self):
        backend = Backend()
        value = event({"kind": "readiness-check"}, {"futurecodeEvent": "readiness-check"})
        self.assertEqual(protect(value, CONFIG, backend, NOW)["action"], "permissions_verified")
        self.assertTrue(backend.permissions_verified)
        self.assertFalse(backend.disabled)

    def test_probe_correlation_is_preserved_and_invalid_probe_ids_are_rejected(self):
        backend = Backend()
        value = event({"kind": "readiness-check"}, {"futurecodeEvent": "readiness-check", "futurecodeProbe": "a" * 32})
        self.assertEqual(protect(value, CONFIG, backend, NOW)["probeId"], "a" * 32)
        value["message"]["attributes"]["futurecodeProbe"] = "invalid"
        backend.permissions_verified = False
        with self.assertRaisesRegex(GuardError, "invalid_probe_id"):
            protect(value, CONFIG, backend, NOW)
        self.assertFalse(backend.permissions_verified)

    def test_optional_quota_alert_is_retired_before_announced_future_alerting_fees(self):
        backend = Backend()
        later = datetime(2027, 8, 31, 12, tzinfo=timezone.utc)
        self.assertEqual(protect(quota(), CONFIG, backend, later)["action"], "quota_alert_disabled_for_pricing_review")
        self.assertEqual(backend.policy_disabled, 1)
        protect(quota(), CONFIG, backend, later)
        self.assertEqual(backend.policy_disabled, 1)
        self.assertFalse(backend.reads)

    def test_pricing_review_is_announced_before_retirement(self):
        backend = Backend()
        protect(quota(), CONFIG, backend, datetime(2027, 8, 1, 12, tzinfo=timezone.utc))
        self.assertIn("futurecode.pricing_review", [name for name, _, _ in backend.emitted])

    def test_bill_shutdown_continues_to_work_after_optional_alert_retirement(self):
        backend = Backend()
        later = datetime(2027, 9, 15, 12, tzinfo=timezone.utc)
        value = budget(5, costIntervalStart=month_start(later).isoformat())
        self.assertEqual(protect(value, CONFIG, backend, later)["action"], "project_billing_disabled")

    def test_calendar_period_uses_pacific_time_including_daylight_saving(self):
        self.assertEqual(month_start(NOW).isoformat(), "2026-10-01T07:00:00+00:00")
        winter = datetime(2026, 12, 15, tzinfo=timezone.utc)
        self.assertEqual(month_start(winter).isoformat(), "2026-12-01T08:00:00+00:00")

    def test_invalid_or_oversized_transport_payloads_are_rejected(self):
        for data in ("?", "x" * 32769, base64.b64encode(b"[]").decode()):
            value = quota()
            value["message"]["data"] = data
            with self.assertRaises(GuardError):
                protect(value, CONFIG, Backend(), NOW)

    def test_project_and_alert_policy_cannot_escape_the_selected_project(self):
        with self.assertRaises(GuardError):
            Config("../project", "budget-test", "state-bucket", "futurecode", "projects/a/alertPolicies/x")
        with self.assertRaises(GuardError):
            Config(CONFIG.project, CONFIG.budget_id, CONFIG.state_bucket, "futurecode", "projects/other/alertPolicies/x")


if __name__ == "__main__":
    unittest.main()
