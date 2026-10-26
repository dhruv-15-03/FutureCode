import copy
import json
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "gcp"))
from review import CHECKS, ReviewError, validate_review


NOW = datetime(2026, 10, 26, 6, tzinfo=timezone.utc)
SCOPE = {
    "project": "futurecode-fixture-project", "repository": "https://github.com/dhruv-15-03/FutureCode.git",
    "region": "us-central1", "billing_account": "billingAccounts/FIXTURE-ONLY"
}


def reviewed():
    return {
        "schemaVersion": 1, "project": SCOPE["project"], "repository": SCOPE["repository"],
        "region": SCOPE["region"], "billingAccount": SCOPE["billing_account"], "currency": "INR",
        "reviewedAt": NOW.isoformat(), "ownerApproved": True, "approvedOperations": ["application", "probes"],
        "bootstrapExposureApproved": False, "alertInboxReceiptConfirmed": True,
        "currentEstimatedNetINR": "1.67", "maximumAdditionalEstimatedINR": "0",
        "eligibleBuildMinutesRemaining": 15, "registryAccountBytes": 1000, "reservedImageGrowthBytes": 1000,
        "checks": {key: {"status": "verified", "evidence": "Synthetic unit-test evidence, not real approval."} for key in CHECKS}
    }


class CostReviewTest(unittest.TestCase):
    def validate(self, data, operation="application"):
        return validate_review(data, **SCOPE, operation=operation, now=NOW)

    def test_complete_scoped_review_has_a_stable_digest(self):
        self.assertEqual(self.validate(reviewed()), self.validate(copy.deepcopy(reviewed())))

    def test_example_is_deliberately_unapproved_and_cannot_enable_cloud_changes(self):
        example = json.loads((Path(__file__).resolve().parents[1] / "gcp" / "cost-review.example.json").read_text("utf-8"))
        with self.assertRaises(ReviewError):
            self.validate(example)

    def test_old_or_other_project_account_currency_reviews_are_rejected(self):
        for key, value in (("project", "other-project"), ("billingAccount", "billingAccounts/OTHER"),
                           ("currency", "USD"), ("repository", "https://example.invalid"),
                           ("reviewedAt", (NOW - timedelta(hours=2)).isoformat())):
            data = reviewed()
            data[key] = value
            with self.subTest(key=key), self.assertRaises(ReviewError):
                self.validate(data)

    def test_every_supporting_product_and_itemized_invoice_needs_evidence(self):
        for key in CHECKS:
            data = reviewed()
            data["checks"][key]["status"] = "unverified"
            with self.subTest(key=key), self.assertRaisesRegex(ReviewError, "evidence_incomplete"):
                self.validate(data)

    def test_no_spend_storage_or_build_headroom_blocks_work(self):
        for key, value in (("currentEstimatedNetINR", "5"), ("maximumAdditionalEstimatedINR", "3.33"),
                           ("registryAccountBytes", 1027478392), ("eligibleBuildMinutesRemaining", 14),
                           ("registryAccountBytes", None), ("maximumAdditionalEstimatedINR", True)):
            data = reviewed()
            data[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ReviewError):
                self.validate(data)

    def test_bootstrap_requires_its_own_approval_and_reserves_both_builds(self):
        data = reviewed()
        data["approvedOperations"].append("bootstrap")
        data["eligibleBuildMinutesRemaining"] = 30
        with self.assertRaisesRegex(ReviewError, "bootstrap_exposure"):
            self.validate(data, "bootstrap")
        data["bootstrapExposureApproved"] = True
        self.validate(data, "bootstrap")
        data["eligibleBuildMinutesRemaining"] = 20
        with self.assertRaisesRegex(ReviewError, "build_allowance"):
            self.validate(data, "bootstrap")

    def test_configured_notification_is_not_confused_with_inbox_receipt(self):
        data = reviewed()
        data["alertInboxReceiptConfirmed"] = False
        with self.assertRaisesRegex(ReviewError, "receipt_confirmation"):
            self.validate(data)

    def test_malformed_approval_data_is_a_review_error_not_an_unhandled_exception(self):
        for key, value in (("schemaVersion", True), ("approvedOperations", [{}]), ("approvedOperations", None)):
            data = reviewed()
            data[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ReviewError):
                self.validate(data)


if __name__ == "__main__":
    unittest.main()
