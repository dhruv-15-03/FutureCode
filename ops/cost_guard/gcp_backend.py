import json
from decimal import Decimal
from urllib.parse import quote

import google.auth
from google.auth.exceptions import GoogleAuthError
from google.auth.transport.requests import AuthorizedSession
from requests import RequestException

from guard import GuardError, amount


class GcpBackend:
    def __init__(self, config):
        self.config = config
        try:
            credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
            self.http = AuthorizedSession(credentials, refresh_timeout=10)
        except GoogleAuthError:
            raise GuardError("cloud_identity_unavailable") from None

    def request(self, method, url, *, body=None, params=None, allow_missing=False, generation_conflict=False):
        allowed = {
            "https://cloudbilling.googleapis.com/",
            "https://monitoring.googleapis.com/",
            "https://storage.googleapis.com/",
            "https://logging.googleapis.com/",
            "https://cloudresourcemanager.googleapis.com/"
        }
        if not any(url.startswith(origin) for origin in allowed):
            raise GuardError("invalid_cloud_destination")
        try:
            with self.http.request(method, url, json=body, params=params, timeout=(5, 20),
                                   allow_redirects=False, stream=True) as response:
                if allow_missing and response.status_code == 404:
                    return None
                if generation_conflict and response.status_code == 412:
                    raise GuardError("state_conflict_retry")
                if not 200 <= response.status_code < 300:
                    raise GuardError("cloud_operation_failed")
                data = bytearray()
                for chunk in response.iter_content(8192):
                    data.extend(chunk)
                    if len(data) > 2 * 1024 * 1024:
                        raise GuardError("cloud_response_too_large")
                result = json.loads(data, parse_float=Decimal) if data else {}
                if not isinstance(result, dict):
                    raise GuardError("invalid_cloud_response")
                return result
        except (RequestException, GoogleAuthError, ValueError):
            raise GuardError("cloud_operation_failed") from None

    def verify_permissions(self):
        result = self.request("POST",
            f"https://cloudresourcemanager.googleapis.com/v1/projects/{self.config.project}:testIamPermissions",
            body={"permissions": ["resourcemanager.projects.deleteBillingAssignment"]})
        if "resourcemanager.projects.deleteBillingAssignment" not in result.get("permissions", []):
            raise GuardError("billing_shutdown_permission_missing")
        billing = self.request("GET", f"https://cloudbilling.googleapis.com/v1/projects/{self.config.project}/billingInfo")
        if billing.get("billingEnabled") is not True:
            raise GuardError("target_billing_not_enabled")

    def disable_billing(self, project):
        if project != self.config.project:
            raise GuardError("project_mismatch")
        url = f"https://cloudbilling.googleapis.com/v1/projects/{project}/billingInfo"
        current = self.request("GET", url)
        if not isinstance(current.get("billingEnabled"), bool):
            raise GuardError("invalid_billing_response")
        if not current["billingEnabled"]:
            return False
        self.request("PUT", url, body={"billingAccountName": ""})
        return True

    def state_url(self, key):
        if len(key) != 7 or not key[:4].isdigit() or key[4] != "-" or key[5:] not in {f"{i:02d}" for i in range(1, 13)}:
            raise GuardError("invalid_state_key")
        name = quote(f"futurecode-cost-state/{key}.json", safe="")
        return f"https://storage.googleapis.com/storage/v1/b/{self.config.state_bucket}/o/{name}"

    def load_state(self, key):
        url = self.state_url(key)
        metadata = self.request("GET", url, params={"fields": "generation"}, allow_missing=True)
        if metadata is None:
            return {}, "0"
        generation = str(metadata.get("generation", ""))
        if not generation.isdigit():
            raise GuardError("invalid_state_generation")
        state = self.request("GET", url, params={"alt": "media", "ifGenerationMatch": generation})
        return state, generation

    def save_state(self, key, state, generation):
        self.state_url(key)
        url = f"https://storage.googleapis.com/upload/storage/v1/b/{self.config.state_bucket}/o"
        self.request("POST", url, params={
            "uploadType": "media", "name": f"futurecode-cost-state/{key}.json",
            "ifGenerationMatch": generation
        }, body=state, generation_conflict=True)

    def read_meter(self, metric, start, end):
        allowed = {
            "run.googleapis.com/request_count",
            "run.googleapis.com/container/billable_instance_time"
        }
        if metric not in allowed:
            raise GuardError("invalid_meter")
        params = {
            "filter": (
                f'metric.type="{metric}" AND resource.type="cloud_run_revision" AND '
                f'(resource.labels.service_name="{self.config.service}" OR '
                f'resource.labels.service_name="{self.config.service}-cost-guard")'
            ),
            "interval.startTime": start.isoformat(),
            "interval.endTime": end.isoformat(),
            "aggregation.alignmentPeriod": "86400s",
            "aggregation.perSeriesAligner": "ALIGN_SUM",
            "aggregation.crossSeriesReducer": "REDUCE_SUM",
            "view": "FULL",
            "pageSize": "100"
        }
        total = Decimal(0)
        for _ in range(5):
            result = self.request("GET", f"https://monitoring.googleapis.com/v3/projects/{self.config.project}/timeSeries", params=params)
            for series in result.get("timeSeries", []):
                if series.get("metricKind") != "DELTA":
                    raise GuardError("unexpected_metric_kind")
                for point in series.get("points", []):
                    value = point.get("value", {})
                    numeric = value.get("int64Value", value.get("doubleValue"))
                    if numeric is None:
                        raise GuardError("invalid_metric_value")
                    total += amount(numeric)
            cursor = result.get("nextPageToken")
            if not cursor:
                return total
            if not isinstance(cursor, str) or len(cursor) > 4096:
                raise GuardError("invalid_metric_cursor")
            params["pageToken"] = cursor
        raise GuardError("metric_page_limit")

    def emit(self, event, details, insert_id):
        self.request("POST", "https://logging.googleapis.com/v2/entries:write", body={
            "logName": f"projects/{self.config.project}/logs/futurecode-cost-guard",
            "resource": {"type": "global", "labels": {"project_id": self.config.project}},
            "entries": [{
                "severity": "WARNING", "insertId": insert_id,
                "jsonPayload": {"event": event, **details}
            }]
        })

    def disable_quota_alert(self):
        self.request("PATCH", f"https://monitoring.googleapis.com/v3/{self.config.alert_policy}",
                     params={"updateMask": "enabled"},
                     body={"name": self.config.alert_policy, "enabled": False})
