import json
import os
import re
import sys
from decimal import Decimal

import functions_framework

from guard import Config, GuardError, protect
from gcp_backend import GcpBackend


@functions_framework.http
def protect_futurecode(request):
    try:
        if request.method != "POST" or request.mimetype != "application/json":
            raise GuardError("json_post_required")
        raw = request.stream.read(32769)
        if len(raw) > 32768:
            raise GuardError("event_too_large")
        try:
            event = json.loads(raw.decode("utf-8"), parse_float=Decimal)
        except (ValueError, UnicodeError):
            raise GuardError("invalid_push_body") from None
        config = Config(os.environ["TARGET_PROJECT"], os.environ["EXPECTED_BUDGET_ID"],
                        os.environ["STATE_BUCKET"], os.environ["APP_SERVICE_NAME"],
                        os.environ["QUOTA_ALERT_POLICY"])
        backend = GcpBackend(config)
        result = protect(event, config, backend)
        if result["action"] not in {"project_billing_disabled", "already_disabled"}:
            backend.emit("futurecode.guard_check", {"action": result["action"], "probeId": result.get("probeId")},
                         f"check-{event.get('message', {}).get('messageId', 'manual')}")
        return "", 204
    except (GuardError, KeyError) as error:
        code = str(error) if isinstance(error, GuardError) else "missing_guard_configuration"
        details = {"event": "futurecode.guard_failure", "code": code}
        if isinstance(locals().get("event"), dict):
            message = event.get("message")
            attributes = message.get("attributes") if isinstance(message, dict) else None
            candidate = attributes.get("futurecodeProbe") if isinstance(attributes, dict) else None
            if isinstance(candidate, str) and re.fullmatch(r"[a-f0-9]{32}", candidate):
                details["probeId"] = candidate
        try:
            if "backend" in locals():
                backend.emit("futurecode.guard_failure", {key: value for key, value in details.items() if key != "event"},
                             "failure-" + code)
        except GuardError:
            details["failureNotification"] = "logging_api_unavailable"
        print(json.dumps(details), file=sys.stderr)
        return {"error": "Cost protection requires operator attention.", "code": code}, 500
