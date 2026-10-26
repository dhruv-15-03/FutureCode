import sys
import unittest
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "cost_guard"))
from guard import Config, GuardError, MeterReading
from gcp_backend import GcpBackend


NOW = datetime(2026, 10, 26, 6, tzinfo=timezone.utc)
METRIC = "run.googleapis.com/request_count"


def series(value, observed=None):
    point = {"value": {"int64Value": str(value)}}
    if observed is not None:
        point["interval"] = {"endTime": observed.isoformat()}
    return {"timeSeries": [{"metricKind": "DELTA", "points": [point]}]}


def backend(*responses):
    result = object.__new__(GcpBackend)
    result.config = Config("futurecode-fixture-project", "fixture-budget-123", "fixture-state",
                           "futurecode", "projects/futurecode-fixture-project/alertPolicies/123")
    result.request = Mock(side_effect=responses)
    return result


class GuardBackendTest(unittest.TestCase):
    def test_missing_metrics_are_unknown_instead_of_numeric_zero(self):
        for response in ({}, {"timeSeries": []}, {"timeSeries": [{"metricKind": "DELTA", "points": []}]}):
            with self.subTest(response=response), self.assertRaisesRegex(GuardError, "telemetry_unavailable"):
                backend(response).read_meter(METRIC, NOW - timedelta(days=1), NOW)

    def test_measured_zero_has_a_fresh_source_timestamp(self):
        value = backend(series(0), series(0, NOW - timedelta(minutes=1))).read_meter(METRIC, NOW - timedelta(days=1), NOW)
        self.assertEqual(value, MeterReading(Decimal(0), NOW - timedelta(minutes=1)))

    def test_monthly_sum_and_raw_sample_freshness_are_separate_requests(self):
        source = backend(series(149), series(1, NOW - timedelta(minutes=2)))
        value = source.read_meter(METRIC, NOW - timedelta(days=1), NOW)
        self.assertEqual(value.value, 149)
        self.assertEqual(value.observed_at, NOW - timedelta(minutes=2))
        self.assertIn("aggregation.alignmentPeriod", source.request.call_args_list[0].kwargs["params"])
        self.assertNotIn("aggregation.alignmentPeriod", source.request.call_args_list[1].kwargs["params"])

    def test_missing_stale_future_or_paginated_freshness_is_not_accepted(self):
        cases = [series(1, NOW - timedelta(minutes=16)), series(1, NOW + timedelta(minutes=2)),
                 {"timeSeries": []}, {"timeSeries": [], "nextPageToken": "incomplete"}]
        for recent in cases:
            with self.subTest(recent=recent), self.assertRaises(GuardError):
                backend(series(149), recent).read_meter(METRIC, NOW - timedelta(days=1), NOW)

    def test_malformed_values_fail_explicitly(self):
        for response in (
            {"timeSeries": ["invalid"]},
            {"timeSeries": [{"metricKind": "GAUGE", "points": []}]},
            {"timeSeries": [{"metricKind": "DELTA", "points": [{"value": None}]}]},
            {"timeSeries": [{"metricKind": "DELTA", "points": [{"value": {"doubleValue": True}}]}]}
        ):
            with self.subTest(response=response), self.assertRaises(GuardError):
                backend(response).read_meter(METRIC, NOW - timedelta(days=1), NOW)

    def test_unknown_metric_cannot_query_arbitrary_billable_data(self):
        source = backend()
        with self.assertRaisesRegex(GuardError, "invalid_meter"):
            source.read_meter("custom.googleapis.com/unknown", NOW - timedelta(days=1), NOW)
        source.request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
