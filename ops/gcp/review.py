import hashlib
import json
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation


CHECKS = {
    "invoice", "cloudRun", "cloudBuild", "artifactRegistry", "cloudStorage",
    "networkEgress", "secretManager", "cloudScheduler", "pubSub", "loggingMonitoring"
}


class ReviewError(Exception):
    pass


def number(value):
    if isinstance(value, bool) or not isinstance(value, (str, int, float)):
        raise ReviewError("cost_review_number_required")
    try:
        result = Decimal(str(value))
    except InvalidOperation:
        raise ReviewError("cost_review_number_required") from None
    if not result.is_finite() or result < 0:
        raise ReviewError("cost_review_number_required")
    return result


def validate_review(data, *, project, repository, region, billing_account, operation, now=None):
    now = now or datetime.now(timezone.utc)
    if not isinstance(data, dict) or type(data.get("schemaVersion")) is not int or data["schemaVersion"] != 1:
        raise ReviewError("invalid_cost_review")
    expected = {"project": project, "repository": repository, "region": region,
                "billingAccount": billing_account, "currency": "INR"}
    if any(data.get(key) != value for key, value in expected.items()):
        raise ReviewError("cost_review_scope_mismatch")
    try:
        reviewed = datetime.fromisoformat(data["reviewedAt"].replace("Z", "+00:00"))
        if reviewed.tzinfo is None or now.tzinfo is None:
            raise ValueError()
        age = (now - reviewed).total_seconds()
    except (KeyError, TypeError, ValueError, AttributeError):
        raise ReviewError("invalid_cost_review_time") from None
    if age < -60 or age > 3600:
        raise ReviewError("cost_review_stale")
    operations = data.get("approvedOperations")
    if (not isinstance(operations, list) or any(not isinstance(item, str) or item not in {"bootstrap", "application", "probes"} for item in operations)
            or operation not in operations or data.get("ownerApproved") is not True):
        raise ReviewError("cost_review_owner_approval_required")
    checks = data.get("checks")
    if not isinstance(checks, dict) or set(checks) != CHECKS:
        raise ReviewError("cost_review_all_products_required")
    for item in checks.values():
        if (not isinstance(item, dict) or item.get("status") != "verified" or
            not isinstance(item.get("evidence"), str) or not 8 <= len(item["evidence"].strip()) <= 1000):
            raise ReviewError("cost_review_evidence_incomplete")
    current, additional = number(data.get("currentEstimatedNetINR")), number(data.get("maximumAdditionalEstimatedINR"))
    if current + additional >= 5:
        raise ReviewError("cost_review_no_spending_headroom")
    planned_builds = sum(item in operations for item in ("bootstrap", "application"))
    if number(data.get("eligibleBuildMinutesRemaining")) < 15 * planned_builds:
        raise ReviewError("cost_review_build_allowance_insufficient")
    stored, growth = number(data.get("registryAccountBytes")), number(data.get("reservedImageGrowthBytes"))
    if stored != stored.to_integral_value() or growth != growth.to_integral_value() or stored + growth >= 512 * 1024 * 1024:
        raise ReviewError("cost_review_registry_headroom_insufficient")
    if operation == "bootstrap" and data.get("bootstrapExposureApproved") is not True:
        raise ReviewError("bootstrap_exposure_requires_separate_approval")
    if operation == "application" and data.get("alertInboxReceiptConfirmed") is not True:
        raise ReviewError("alert_receipt_confirmation_required")
    return hashlib.sha256(json.dumps(data, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
