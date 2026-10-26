import base64
import binascii
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from zoneinfo import ZoneInfo


class GuardError(Exception):
    pass


@dataclass(frozen=True)
class MeterReading:
    value: Decimal
    observed_at: datetime


POLICY = json.loads(Path(__file__).with_name("policy.json").read_text("utf-8"))


def amount(value):
    if isinstance(value, bool) or not isinstance(value, (str, int, Decimal, float)):
        raise GuardError("invalid_amount")
    try:
        result = Decimal(str(value))
    except InvalidOperation:
        raise GuardError("invalid_amount") from None
    if not result.is_finite():
        raise GuardError("invalid_amount")
    return result


@dataclass(frozen=True)
class Config:
    project: str
    budget_id: str
    state_bucket: str
    service: str
    alert_policy: str

    def __post_init__(self):
        if not re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", self.project):
            raise GuardError("invalid_project")
        if not re.fullmatch(r"[A-Za-z0-9_-]{8,128}", self.budget_id):
            raise GuardError("invalid_budget_id")
        if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", self.state_bucket):
            raise GuardError("invalid_state_bucket")
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,61}[a-z0-9]", self.service):
            raise GuardError("invalid_service")
        prefix = f"projects/{self.project}/alertPolicies/"
        if not self.alert_policy.startswith(prefix) or not re.fullmatch(
            r"[A-Za-z0-9_-]+", self.alert_policy[len(prefix):]
        ):
            raise GuardError("invalid_alert_policy")


def month_start(now):
    local = now.astimezone(ZoneInfo(POLICY["calendar_timezone"]))
    return local.replace(day=1, hour=0, minute=0, second=0, microsecond=0).astimezone(timezone.utc)


def decode_event(event):
    message = event.get("message") if isinstance(event, dict) else None
    if not isinstance(message, dict) or not isinstance(message.get("attributes", {}), dict):
        raise GuardError("invalid_envelope")
    encoded = message.get("data")
    if not isinstance(encoded, str) or len(encoded) > 32768:
        raise GuardError("invalid_event_size")
    try:
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) > 16384:
            raise GuardError("invalid_event_size")
        body = json.loads(raw.decode("utf-8"), parse_float=Decimal)
    except (ValueError, UnicodeError, binascii.Error):
        raise GuardError("invalid_event_encoding") from None
    if not isinstance(body, dict):
        raise GuardError("invalid_event_body")
    return body, message.get("attributes", {})


def protect(event, config, backend, now=None):
    now = now or datetime.now(timezone.utc)
    if now.tzinfo is None:
        raise GuardError("timezone_required")
    expected_subscription = f"projects/{config.project}/subscriptions/{config.service}-cost-guard"
    if not isinstance(event, dict) or event.get("subscription") != expected_subscription:
        raise GuardError("unexpected_subscription")
    body, attributes = decode_event(event)
    probe_id = attributes.get("futurecodeProbe")
    if probe_id is not None and (not isinstance(probe_id, str) or not re.fullmatch(r"[a-f0-9]{32}", probe_id)):
        raise GuardError("invalid_probe_id")
    if attributes.get("futurecodeEvent") == "readiness-check":
        if body != {"kind": "readiness-check"}:
            raise GuardError("invalid_readiness_event")
        backend.verify_permissions()
        return {"action": "permissions_verified", "probeId": probe_id}
    if attributes.get("futurecodeEvent") == "quota-check":
        if body != {"kind": "quota-check"}:
            raise GuardError("invalid_quota_event")
        return {**check_quota(config, backend, now), "probeId": probe_id}
    if attributes.get("budgetId") != config.budget_id or attributes.get("schemaVersion") != "1.0":
        raise GuardError("unexpected_budget")
    if body.get("currencyCode") != POLICY["currency"]:
        raise GuardError("currency_mismatch")
    if amount(body.get("budgetAmount")) != amount(POLICY["monthly_net_target"]):
        raise GuardError("unexpected_budget_amount")
    try:
        interval = datetime.fromisoformat(body["costIntervalStart"].replace("Z", "+00:00"))
    except (KeyError, TypeError, ValueError, AttributeError):
        raise GuardError("invalid_billing_period") from None
    if interval.tzinfo is None:
        raise GuardError("invalid_billing_period")
    start = month_start(now)
    if interval < start:
        return {"action": "ignored_previous_period"}
    if interval != start:
        raise GuardError("unexpected_billing_period")
    cost = amount(body.get("costAmount"))
    published = event["message"].get("publishTime")
    if published is not None:
        try:
            published_at = datetime.fromisoformat(published.replace("Z", "+00:00"))
            if published_at.tzinfo is None:
                raise ValueError()
            published = published_at.astimezone(timezone.utc).isoformat()
        except (ValueError, TypeError, AttributeError):
            raise GuardError("invalid_notification_time") from None
    observation = {
        "amount": str(cost), "currency": POLICY["currency"], "periodStart": interval.isoformat(),
        "notificationPublishedAt": published, "receivedAt": now.astimezone(timezone.utc).isoformat(),
        "usageReportingFreshness": "Not supplied by the budget notification; estimated costs can arrive late."
    }
    message_id = event["message"].get("messageId", "")
    if not isinstance(message_id, str) or len(message_id) > 256:
        raise GuardError("invalid_message_id")
    observation_id = hashlib.sha256((config.budget_id + ":" + message_id + ":" + str(cost)).encode()).hexdigest()
    try:
        backend.emit("futurecode.cost_observation", observation, "cost-" + observation_id)
    except GuardError:
        if cost < amount(POLICY["monthly_net_target"]):
            raise GuardError("cost_observation_failed") from None
        print(json.dumps({"event": "futurecode.guard_failure", "code": "cost_observation_failed"}), file=sys.stderr)
    if cost < amount(POLICY["monthly_net_target"]):
        return {"action": "below_target"}
    try:
        backend.emit("futurecode.billing_stop_requested", {
            "currency": POLICY["currency"], "target": POLICY["monthly_net_target"],
            "notice": "Estimated net target reached; final costs and taxes can exceed it."
        }, f"stop-{start.isoformat()}")
    except GuardError:
        print(json.dumps({"event": "futurecode.guard_failure", "code": "stop_log_failed"}), file=sys.stderr)
    disabled = backend.disable_billing(config.project)
    return {"action": "project_billing_disabled" if disabled else "already_disabled"}


def check_quota(config, backend, now):
    start = month_start(now)
    key = start.astimezone(ZoneInfo(POLICY["calendar_timezone"])).strftime("%Y-%m")
    state, generation = backend.load_state(key)
    if not isinstance(state, dict) or set(state) - {"crossed", "pricing_warned", "alert_disabled"}:
        raise GuardError("invalid_quota_state")
    crossed = state.get("crossed", [])
    names = {meter["name"] for meter in POLICY["meters"]}
    if not isinstance(crossed, list) or any(name not in names for name in crossed) or len(crossed) != len(set(crossed)):
        raise GuardError("invalid_quota_state")
    for flag in ("pricing_warned", "alert_disabled"):
        if flag in state and not isinstance(state[flag], bool):
            raise GuardError("invalid_quota_state")
    if now.date() >= date.fromisoformat(POLICY["quota_alert_sunset"]):
        if not state.get("alert_disabled"):
            backend.disable_quota_alert()
            state["alert_disabled"] = True
            backend.save_state(key, state, generation)
        return {"action": "quota_alert_disabled_for_pricing_review"}
    review = date.fromisoformat(POLICY["quota_alert_review"])
    if now.date() >= review and not state.get("pricing_warned"):
        backend.emit("futurecode.pricing_review", {
            "notice": "Review Cloud Monitoring pricing before the optional quota alert is disabled.",
            "disableOn": POLICY["quota_alert_sunset"]
        }, f"pricing-{review.isoformat()}")
        state["pricing_warned"] = True
    usage, newly_crossed, samples = {}, [], {}
    for meter in POLICY["meters"]:
        if meter["metric"] not in samples:
            reading = backend.read_meter(meter["metric"], start, now)
            if not isinstance(reading, MeterReading) or reading.observed_at.tzinfo is None:
                raise GuardError("telemetry_unavailable")
            age = (now - reading.observed_at).total_seconds()
            if age < -60 or age > 900:
                raise GuardError("telemetry_stale")
            samples[meter["metric"]] = reading
        reading = samples[meter["metric"]]
        raw = amount(reading.value)
        if raw < 0:
            raise GuardError("negative_usage")
        used = raw / amount(meter["divisor"])
        usage[meter["name"]] = {"used": str(used), "monthlyAllowance": meter["monthly_free"],
                               "observedAt": reading.observed_at.isoformat()}
        if used >= amount(meter["monthly_free"]) * amount(POLICY["warning_fraction"]) and meter["name"] not in crossed:
            newly_crossed.append(meter["name"])
    if newly_crossed:
        backend.emit("futurecode.quota_half", {
            "newlyCrossed": newly_crossed, "usage": usage,
            "notice": "Estimated month-to-date app usage, not an authoritative billing-account free-credit balance."
        }, f"quota-{key}-{'-'.join(newly_crossed)}")
    state["crossed"] = crossed + newly_crossed
    backend.save_state(key, state, generation)
    return {"action": "quota_checked", "newlyCrossed": newly_crossed}
