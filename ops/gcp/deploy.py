import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

import requests


ROOT = Path(__file__).resolve().parents[2]
POLICY = json.loads((ROOT / "ops" / "cost_guard" / "policy.json").read_text("utf-8"))
EXPECTED_REMOTE = "https://github.com/dhruv-15-03/FutureCode.git"
PROJECT = json.loads((ROOT / "ops" / "gcp" / "project.json").read_text("utf-8"))
LABELS = {"app": "futurecode", "managed-by": "futurecode-deploy", "repository": "dhruv-15-03-futurecode"}
APIS = [
    "cloudbilling.googleapis.com", "billingbudgets.googleapis.com", "cloudresourcemanager.googleapis.com",
    "serviceusage.googleapis.com", "iam.googleapis.com", "run.googleapis.com", "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com", "storage.googleapis.com", "pubsub.googleapis.com",
    "cloudscheduler.googleapis.com", "monitoring.googleapis.com", "logging.googleapis.com",
    "secretmanager.googleapis.com"
]


class DeployError(Exception):
    pass


class Deployment:
    def __init__(self, project, region, state_file=None):
        if not re.fullmatch(r"[a-z][a-z0-9-]{4,28}[a-z0-9]", project):
            raise DeployError("invalid_project")
        approved = os.environ.get("FUTURECODE_APPROVED_GCP_PROJECT")
        if not approved or approved != project or project != PROJECT["project"]:
            raise DeployError("project_not_explicitly_approved")
        if region != "us-central1":
            raise DeployError("region_requires_free_tier_review")
        self.project, self.region = project, region
        executable = shutil.which("gcloud") or shutil.which("gcloud.cmd")
        if not executable:
            raise DeployError("gcloud_missing")
        sdk = Path(executable).resolve().parents[1]
        self.command = [os.environ.get("CLOUDSDK_PYTHON", sys.executable), str(sdk / "lib" / "gcloud.py")]
        self.session = requests.Session()
        self.token = None
        self.project_number = None
        self.billing_account = None
        self.currency = None
        self.state_file = Path(state_file).resolve() if state_file else None
        if self.state_file and (self.state_file == ROOT or ROOT in self.state_file.parents):
            raise DeployError("deployment_state_must_be_outside_repository")
        self.state = {}
        if self.state_file and self.state_file.exists():
            self.state = json.loads(self.state_file.read_text("utf-8"))
            if self.state.get("project") != project:
                raise DeployError("deployment_state_project_mismatch")
        self.state["project"] = project
        self.service = PROJECT["service"]
        self.guard_service = self.service + "-cost-guard"
        self.bucket = project + "-futurecode-ops"
        self.repository = "futurecode"
        self.topic = f"projects/{project}/topics/futurecode-cost-events"
        self.subscription = f"projects/{project}/subscriptions/{self.guard_service}"
        self.guard_identity = f"futurecode-cost-guard@{project}.iam.gserviceaccount.com"
        self.push_identity = f"futurecode-cost-invoke@{project}.iam.gserviceaccount.com"
        self.build_identity = f"futurecode-build@{project}.iam.gserviceaccount.com"
        self.app_identity = f"futurecode-app@{project}.iam.gserviceaccount.com"

    def gcloud(self, *arguments, raw=False, input_bytes=None, timeout=180):
        command = [*self.command, *arguments, f"--project={self.project}", "--quiet"]
        if not raw:
            command.append("--format=json")
        environment = os.environ.copy()
        environment.pop("CLOUDSDK_BILLING_QUOTA_PROJECT", None)
        result = subprocess.run(command, input=input_bytes, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout, cwd=ROOT, env=environment)
        if result.returncode:
            known = re.search(
                rb"\b(PERMISSION_DENIED|NOT_FOUND|FAILED_PRECONDITION|ALREADY_EXISTS|INVALID_ARGUMENT|UNAUTHENTICATED|RESOURCE_EXHAUSTED|SERVICE_DISABLED|USER_PROJECT_DENIED)\b",
                result.stderr)
            suffix = known.group(1).decode() if known else "UNSPECIFIED"
            raise DeployError(f"gcloud_{arguments[0]}_{suffix}")
        if raw:
            return result.stdout.decode("utf-8").strip()
        try:
            return json.loads(result.stdout)
        except ValueError:
            raise DeployError("gcloud_invalid_json") from None

    def api(self, method, url, *, body=None, params=None, allow_missing=False):
        origins = {
            "https://cloudbilling.googleapis.com/",
            "https://billingbudgets.googleapis.com/",
            "https://monitoring.googleapis.com/",
            "https://pubsub.googleapis.com/",
            "https://cloudscheduler.googleapis.com/",
            "https://cloudresourcemanager.googleapis.com/",
            "https://cloudbuild.googleapis.com/",
            "https://logging.googleapis.com/"
        }
        if not any(url.startswith(origin) for origin in origins):
            raise DeployError("invalid_control_plane_destination")
        if self.token is None:
            self.token = self.gcloud("auth", "print-access-token", raw=True)
        headers = {"Authorization": "Bearer " + self.token, "x-goog-user-project": self.project}
        try:
            response = self.session.request(method, url, json=body, params=params, headers=headers,
                                            timeout=(10, 60), allow_redirects=False)
            if allow_missing and response.status_code == 404:
                return None
            if not 200 <= response.status_code < 300:
                try:
                    status = response.json().get("error", {}).get("status", "UNSPECIFIED")
                except ValueError:
                    status = "UNSPECIFIED"
                if not re.fullmatch(r"[A-Z_]{1,60}", status):
                    status = "UNSPECIFIED"
                raise DeployError(f"cloud_api_{status}")
            return response.json() if response.content else {}
        except (requests.RequestException, ValueError):
            raise DeployError("control_plane_request_failed") from None

    def preflight(self):
        remote = subprocess.run(["git", "remote", "get-url", "origin"], cwd=ROOT,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        if remote.stdout.decode().strip() != EXPECTED_REMOTE:
            raise DeployError("unexpected_source_repository")
        project = self.gcloud("projects", "describe", self.project)
        if project.get("projectId") != self.project or project.get("lifecycleState") != "ACTIVE":
            raise DeployError("target_project_not_active")
        self.project_number = str(project["projectNumber"])
        linked = self.gcloud("billing", "projects", "describe", self.project)
        if linked.get("billingEnabled") is not True:
            raise DeployError("billing_not_enabled_user_verification_required")
        account = linked.get("billingAccountName", "")
        if not re.fullmatch(r"billingAccounts/[A-Z0-9-]+", account):
            raise DeployError("invalid_linked_billing_account")
        metadata = self.gcloud("billing", "accounts", "describe", account.removeprefix("billingAccounts/"))
        self.currency = metadata.get("currencyCode")
        if self.currency != POLICY["currency"]:
            raise DeployError("billing_currency_is_not_INR")
        self.billing_account = account
        return {
            "project": self.project, "projectActive": True, "billingEnabled": True,
            "currency": self.currency, "repositoryVerified": True,
            "monthlyNetTarget": POLICY["monthly_net_target"],
            "spendAlerts": POLICY["spend_alerts"],
            "nativeGrossCapEnabled": False,
            "cloudResourcesCreatedByPreflight": False
        }

    def save(self):
        if self.state_file is None:
            raise DeployError("deployment_state_file_required")
        self.state_file.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.state_file.with_suffix(".tmp")
        temporary.write_text(json.dumps(self.state, indent=2), "utf-8")
        temporary.replace(self.state_file)

    def owned(self, resource):
        if any(resource.get("labels", {}).get(key) != value for key, value in LABELS.items()):
            raise DeployError("existing_resource_is_not_owned_by_futurecode")

    def account(self, name):
        email = f"{name}@{self.project}.iam.gserviceaccount.com"
        rows = self.gcloud("iam", "service-accounts", "list", f"--filter=email:{email}")
        if not any(row.get("email") == email for row in rows):
            self.gcloud("iam", "service-accounts", "create", name, "--display-name=" + name)
        return email

    def project_role(self, email, role):
        self.gcloud("projects", "add-iam-policy-binding", self.project,
                    "--member=serviceAccount:" + email, "--role=" + role, "--condition=None")

    def notification_channel(self):
        base = f"https://monitoring.googleapis.com/v3/projects/{self.project}/notificationChannels"
        channels = self.api("GET", base).get("notificationChannels", [])
        title = "FutureCode quota and cost warnings"
        matches = [item for item in channels if item.get("displayName") == title]
        if len(matches) > 1:
            raise DeployError("ambiguous_notification_channel")
        if matches:
            channel = matches[0]
        else:
            owners = self.gcloud("auth", "list", "--filter=status:ACTIVE")
            if len(owners) != 1 or not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", owners[0].get("account", "")):
                raise DeployError("active_notification_recipient_unavailable")
            channel = self.api("POST", base, body={
                "type": "email", "displayName": title, "labels": {"email_address": owners[0]["account"]},
                "enabled": True, "userLabels": LABELS
            })
        if channel.get("type") != "email" or channel.get("enabled") is not True:
            raise DeployError("notification_channel_not_enabled")
        self.state["notificationChannel"] = channel["name"]
        self.state["notificationVerification"] = channel.get("verificationStatus", "VERIFICATION_STATUS_UNSPECIFIED")
        return channel["name"]

    def quota_alert(self, channel):
        base = f"https://monitoring.googleapis.com/v3/projects/{self.project}/alertPolicies"
        rows = self.api("GET", base).get("alertPolicies", [])
        title = "FutureCode free-usage half-way and protector health"
        matches = [row for row in rows if row.get("displayName") == title]
        if len(matches) > 1:
            raise DeployError("ambiguous_quota_alert")
        body = {
            "displayName": title, "combiner": "OR", "enabled": True, "userLabels": LABELS,
            "conditions": [{
                "displayName": "Free-usage threshold or guardian failure",
                "conditionMatchedLog": {
                    "filter": (
                        f'(logName="projects/{self.project}/logs/futurecode-cost-guard" '
                        'AND (jsonPayload.event="futurecode.quota_half" OR '
                        'jsonPayload.event="futurecode.pricing_review" OR '
                        'jsonPayload.event="futurecode.guard_failure" OR '
                        'jsonPayload.event="futurecode.protection_test")) OR '
                        f'(resource.type="cloud_run_revision" AND resource.labels.service_name="{self.guard_service}" '
                        'AND (jsonPayload.event="futurecode.guard_failure" OR severity>=ERROR))'
                    )
                }
            }],
            "notificationChannels": [channel],
            "alertStrategy": {"notificationRateLimit": {"period": "3600s"}, "autoClose": "1800s"},
            "documentation": {"mimeType": "text/markdown", "content": (
                "FutureCode's project-scoped quota estimate crossed 50%, cost protection requires attention, "
                "or an explicitly labeled delivery test ran. Review the matching log entry. "
                "This is not the billing account's authoritative remaining free-credit balance. "
                "Optional log alerting is disabled by 2027-08-31 pending the announced pricing review."
            )}
        }
        if matches:
            policy = matches[0]
            self.owned(policy)
            body["name"] = policy["name"]
            policy = self.api("PATCH", "https://monitoring.googleapis.com/v3/" + policy["name"], body=body)
        else:
            policy = self.api("POST", base, body=body)
        self.state["quotaAlert"] = policy["name"]
        self.save()
        return policy["name"]

    def budget(self, channel):
        base = f"https://billingbudgets.googleapis.com/v1/{self.billing_account}/budgets"
        budgets = self.api("GET", base).get("budgets", [])
        title = f"FutureCode {self.project} INR 5 net stop"
        matches = [item for item in budgets if item.get("displayName") == title]
        if len(matches) > 1:
            raise DeployError("ambiguous_project_budget")
        body = {
            "displayName": title,
            "budgetFilter": {
                "projects": ["projects/" + self.project_number], "calendarPeriod": "MONTH",
                "creditTypesTreatment": "INCLUDE_ALL_CREDITS"
            },
            "amount": {"specifiedAmount": {"currencyCode": POLICY["currency"], "units": POLICY["monthly_net_target"]}},
            "thresholdRules": [{"thresholdPercent": float(value) / 5, "spendBasis": "CURRENT_SPEND"}
                               for value in POLICY["spend_alerts"]],
            "notificationsRule": {
                "pubsubTopic": self.topic, "schemaVersion": "1.0",
                "monitoringNotificationChannels": [channel], "disableDefaultIamRecipients": True,
                "enableProjectLevelRecipients": True
            }
        }
        if matches:
            old = matches[0]
            if old.get("budgetFilter", {}).get("projects") != body["budgetFilter"]["projects"]:
                raise DeployError("budget_project_scope_mismatch")
            body["etag"] = old["etag"]
            body["name"] = old["name"]
            budget = self.api("PATCH", "https://billingbudgets.googleapis.com/v1/" + old["name"], body=body)
        else:
            budget = self.api("POST", base, body=body)
        self.state["budget"] = budget["name"]
        self.save()
        return budget["name"].split("/")[-1]

    def infrastructure(self):
        self.gcloud("services", "enable", *APIS, timeout=360)
        for name in ("futurecode-cost-guard", "futurecode-cost-invoke", "futurecode-build", "futurecode-app"):
            self.account(name)
        role_id = "futurecodeCostGuardian"
        roles = self.gcloud("iam", "roles", "list", "--filter=name:" + role_id)
        permissions = [
            "resourcemanager.projects.get", "resourcemanager.projects.deleteBillingAssignment",
            "monitoring.alertPolicies.get", "monitoring.alertPolicies.update"
        ]
        command = "update" if any(item["name"].endswith("/" + role_id) for item in roles) else "create"
        self.gcloud("iam", "roles", command, role_id, "--title=FutureCode scoped cost protector",
                    "--permissions=" + ",".join(permissions), "--stage=GA")
        self.project_role(self.guard_identity, f"projects/{self.project}/roles/{role_id}")
        self.project_role(self.guard_identity, "roles/monitoring.viewer")
        self.project_role(self.guard_identity, "roles/logging.logWriter")
        self.project_role(self.build_identity, "roles/logging.logWriter")
        buckets = self.gcloud("storage", "buckets", "list")
        existing = [bucket for bucket in buckets if bucket.get("name", "").removeprefix("gs://").rstrip("/") == self.bucket]
        if existing:
            self.owned(existing[0])
        else:
            self.gcloud("storage", "buckets", "create", "gs://" + self.bucket, "--location=" + self.region,
                        "--uniform-bucket-level-access", "--public-access-prevention", "--soft-delete-duration=0")
            self.gcloud("storage", "buckets", "update", "gs://" + self.bucket,
                        "--update-labels=" + ",".join(key + "=" + value for key, value in LABELS.items()))
        for email, role in ((self.guard_identity, "roles/storage.objectUser"), (self.build_identity, "roles/storage.objectViewer")):
            self.gcloud("storage", "buckets", "add-iam-policy-binding", "gs://" + self.bucket,
                        "--member=serviceAccount:" + email, "--role=" + role)
        self.gcloud("storage", "buckets", "update", "gs://" + self.bucket,
                    "--lifecycle-file=" + str(ROOT / "ops" / "gcp" / "storage-lifecycle.json"))
        repositories = self.gcloud("artifacts", "repositories", "list", "--location=" + self.region)
        existing = [row for row in repositories if row.get("name", "").endswith("/" + self.repository)]
        if existing:
            self.owned(existing[0])
        else:
            self.gcloud("artifacts", "repositories", "create", self.repository, "--repository-format=docker",
                        "--location=" + self.region, "--labels=" + ",".join(key + "=" + value for key, value in LABELS.items()))
        self.gcloud("artifacts", "repositories", "add-iam-policy-binding", self.repository,
                    "--location=" + self.region, "--member=serviceAccount:" + self.build_identity,
                    "--role=roles/artifactregistry.writer")
        self.gcloud("artifacts", "repositories", "set-cleanup-policies", self.repository,
                    "--location=" + self.region,
                    "--policy=" + str(ROOT / "ops" / "gcp" / "artifact-cleanup.json"), "--no-dry-run")
        topic = self.api("GET", "https://pubsub.googleapis.com/v1/" + self.topic, allow_missing=True)
        if topic is None:
            self.api("PUT", "https://pubsub.googleapis.com/v1/" + self.topic,
                     body={"name": self.topic, "labels": LABELS})
        else:
            self.owned(topic)
        self.state["stateBucket"] = self.bucket
        self.save()

    def source_image(self, target):
        if target not in {"app", "cost-guard"}:
            raise DeployError("unknown_build_target")
        if subprocess.run(["git", "status", "--porcelain"], cwd=ROOT, capture_output=True, check=True).stdout:
            raise DeployError("commit_source_before_building")
        commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, check=True).stdout.decode().strip()
        tag = f"{self.region}-docker.pkg.dev/{self.project}/{self.repository}/{target}:{commit[:12]}"
        old = self.state.get("images", {}).get(target)
        if old and old.get("commit") == commit:
            return old["image"]
        with tempfile.TemporaryDirectory(prefix="futurecode-source-") as directory:
            archive = Path(directory) / "source.tar.gz"
            with archive.open("wb") as output:
                subprocess.run(["git", "archive", "--format=tar.gz", "HEAD"], cwd=ROOT,
                               stdout=output, stderr=subprocess.PIPE, check=True)
            object_name = f"sources/{commit}.tar.gz"
            self.gcloud("storage", "cp", str(archive), f"gs://{self.bucket}/{object_name}", raw=True)
        build = self.api("POST", f"https://cloudbuild.googleapis.com/v1/projects/{self.project}/locations/{self.region}/builds", body={
            "source": {"storageSource": {"bucket": self.bucket, "object": object_name}},
            "steps": [{
                "name": "gcr.io/cloud-builders/docker",
                "args": ["build", "-f", "Dockerfile" if target == "app" else "ops/cost_guard/Dockerfile",
                         "--label", "org.opencontainers.image.revision=" + commit, "-t", tag, "."]
            }],
            "images": [tag], "timeout": "900s",
            "serviceAccount": f"projects/{self.project}/serviceAccounts/{self.build_identity}",
            "options": {"logging": "CLOUD_LOGGING_ONLY", "machineType": "E2_MEDIUM"},
            "tags": ["futurecode", target]
        })
        build_name = build.get("metadata", {}).get("build", {}).get("name")
        if not build_name:
            raise DeployError("build_operation_missing")
        self.state["activeBuild"] = build_name
        self.save()
        for _ in range(190):
            current = self.api("GET", "https://cloudbuild.googleapis.com/v1/" + build_name)
            status = current.get("status")
            if status == "SUCCESS":
                digest = current.get("results", {}).get("images", [{}])[0].get("digest")
                if not isinstance(digest, str) or not re.fullmatch(r"sha256:[a-f0-9]{64}", digest):
                    raise DeployError("built_image_digest_missing")
                image = tag.rsplit(":", 1)[0] + "@" + digest
                self.state.setdefault("images", {})[target] = {"image": image, "commit": commit}
                self.state.pop("activeBuild", None)
                self.save()
                return image
            if status not in {"STATUS_UNKNOWN", "PENDING", "QUEUED", "WORKING"}:
                raise DeployError("image_build_" + str(status).lower())
            time.sleep(5)
        raise DeployError("image_build_timeout")

    def service_deploy(self, name, image, identity, variables, *, secrets=None):
        if name == self.service:
            document = self.app_service_document(image, identity, variables, secrets)
            with tempfile.TemporaryDirectory(prefix="futurecode-service-") as directory:
                file = Path(directory) / "service.json"
                file.write_text(json.dumps(document), "utf-8")
                self.gcloud("run", "services", "replace", str(file), "--region=" + self.region, timeout=600)
            return self.service_url(name)
        arguments = [
            "run", "deploy", name, "--image=" + image, "--region=" + self.region,
            "--service-account=" + identity, "--cpu=1", "--memory=512Mi", "--min=0", "--max=1",
            "--concurrency=" + ("1" if name == self.guard_service else "16"), "--timeout=60",
            "--cpu-throttling", "--no-cpu-boost", "--port=8080", "--ingress=all",
            "--no-allow-unauthenticated", "--labels=" + ",".join(key + "=" + value for key, value in LABELS.items()),
            "--set-env-vars=" + ",".join(key + "=" + value for key, value in variables.items())
        ]
        if secrets:
            arguments.append("--set-secrets=" + ",".join(path + "=" + name + ":" + version
                                                        for path, (name, version) in secrets.items()))
        self.gcloud(*arguments, timeout=600)
        return self.service_url(name)

    def app_service_document(self, image, identity, variables, secrets):
        origin = variables.get("APP_ORIGIN", "")
        if not re.fullmatch(r"https://[a-z0-9.-]+\.run\.app", origin):
            raise DeployError("invalid_app_probe_origin")
        if identity != self.app_identity or not secrets:
            raise DeployError("invalid_app_service_identity")
        mounts, volumes = [], []
        for path, (name, version) in secrets.items():
            directory, filename = path.rsplit("/", 1)
            mounts.append({"name": name, "mountPath": directory})
            volumes.append({"name": name, "secret": {"secretName": name, "items": [{"key": version, "path": filename}]}})
        return {
            "apiVersion": "serving.knative.dev/v1", "kind": "Service",
            "metadata": {"name": self.service, "namespace": self.project, "labels": LABELS,
                         "annotations": {"run.googleapis.com/maxScale": "1", "run.googleapis.com/ingress": "all"}},
            "spec": {
                "template": {
                    "metadata": {"annotations": {
                        "run.googleapis.com/cpu-throttling": "true", "run.googleapis.com/startup-cpu-boost": "false"
                    }},
                    "spec": {
                        "serviceAccountName": identity, "containerConcurrency": 16, "timeoutSeconds": 60,
                        "volumes": volumes, "containers": [{
                            "image": image, "ports": [{"containerPort": 8080}],
                            "env": [{"name": key, "value": value} for key, value in variables.items()],
                            "resources": {"limits": {"cpu": "1", "memory": "512Mi"}},
                            "volumeMounts": mounts,
                            "startupProbe": {
                                "httpGet": {"path": "/readyz", "port": 8080,
                                            "httpHeaders": [{"name": "Host", "value": urlparse(origin).netloc}]},
                                "periodSeconds": 5, "timeoutSeconds": 5, "failureThreshold": 48
                            }
                        }]
                    }
                },
                "traffic": [{"latestRevision": True, "percent": 100}]
            }
        }

    def service_url(self, name):
        service = self.gcloud("run", "services", "describe", name, "--region=" + self.region)
        if not any(condition.get("type") == "Ready" and condition.get("status") == "True"
                   for condition in service.get("status", {}).get("conditions", [])):
            raise DeployError("cloud_run_service_not_ready")
        url = service.get("status", {}).get("url", "")
        if not re.fullmatch(r"https://[a-z0-9.-]+\.run\.app", url):
            raise DeployError("invalid_cloud_run_url")
        return url

    def protect(self):
        self.infrastructure()
        channel = self.notification_channel()
        alert = self.quota_alert(channel)
        budget_id = self.budget(channel)
        self.save()
        image = self.source_image("cost-guard")
        url = self.service_deploy(self.guard_service, image, self.guard_identity, {
            "TARGET_PROJECT": self.project, "EXPECTED_BUDGET_ID": budget_id, "STATE_BUCKET": self.bucket,
            "APP_SERVICE_NAME": self.service, "QUOTA_ALERT_POLICY": alert
        })
        self.state["guardUrl"] = url
        self.gcloud("run", "services", "add-iam-policy-binding", self.guard_service,
                    "--region=" + self.region, "--member=serviceAccount:" + self.push_identity,
                    "--role=roles/run.invoker")
        pubsub_agent = f"service-{self.project_number}@gcp-sa-pubsub.iam.gserviceaccount.com"
        self.gcloud("iam", "service-accounts", "add-iam-policy-binding", self.push_identity,
                    "--member=serviceAccount:" + pubsub_agent, "--role=roles/iam.serviceAccountTokenCreator")
        subscription_url = "https://pubsub.googleapis.com/v1/" + self.subscription
        current = self.api("GET", subscription_url, allow_missing=True)
        desired = {
            "name": self.subscription, "topic": self.topic, "ackDeadlineSeconds": 60,
            "messageRetentionDuration": "86400s",
            "pushConfig": {"pushEndpoint": url, "oidcToken": {
                "serviceAccountEmail": self.push_identity, "audience": url
            }},
            "retryPolicy": {"minimumBackoff": "30s", "maximumBackoff": "600s"},
            "expirationPolicy": {}, "labels": LABELS
        }
        if current is None:
            self.api("PUT", subscription_url, body=desired)
        else:
            self.owned(current)
            if current.get("topic") != self.topic:
                raise DeployError("subscription_topic_mismatch")
            self.api("POST", subscription_url + ":modifyPushConfig", body={"pushConfig": desired["pushConfig"]})
        parent = f"projects/{self.project}/locations/{self.region}"
        job_name = parent + "/jobs/futurecode-hourly-quota-check"
        job_url = "https://cloudscheduler.googleapis.com/v1/" + job_name
        job = {
            "name": job_name, "schedule": "13 * * * *", "timeZone": "Etc/UTC",
            "description": "FutureCode hourly project-scoped quota estimate; no paid synthetic monitor.",
            "pubsubTarget": {"topicName": self.topic,
                "data": base64.b64encode(b'{"kind":"quota-check"}').decode(),
                "attributes": {"futurecodeEvent": "quota-check"}},
            "retryConfig": {"retryCount": 1, "minBackoffDuration": "30s", "maxBackoffDuration": "60s"}
        }
        if self.api("GET", job_url, allow_missing=True) is None:
            self.api("POST", f"https://cloudscheduler.googleapis.com/v1/{parent}/jobs", body=job)
        else:
            self.api("PATCH", job_url, body=job,
                     params={"updateMask": "schedule,timeZone,pubsubTarget,retryConfig,description"})
        self.state["scheduler"] = job_name
        self.state["protectionProbeStarted"] = datetime.now(timezone.utc).isoformat()
        self.save()
        self.publish_check("readiness-check")
        self.publish_check("quota-check")
        return {"project": self.project, "protectionConfigured": True, "guardPrivate": True,
                "spendAlertThresholds": POLICY["spend_alerts"], "netShutdownTarget": "5",
                "finalInvoiceCapGuaranteed": False}

    def publish_check(self, kind):
        if kind not in {"readiness-check", "quota-check"}:
            raise DeployError("invalid_protection_probe")
        return self.api("POST", "https://pubsub.googleapis.com/v1/" + self.topic + ":publish", body={
            "messages": [{"data": base64.b64encode(json.dumps({"kind": kind}).encode()).decode(),
                          "attributes": {"futurecodeEvent": kind}}]
        })

    def secret(self, name, data=None, file=None):
        existing = self.gcloud("secrets", "list", "--filter=name:" + name)
        match = [row for row in existing if row["name"].endswith("/" + name)]
        if match:
            self.owned(match[0])
            versions = self.gcloud("secrets", "versions", "list", name,
                                   "--filter=state:ENABLED", "--sort-by=~createTime", "--limit=1")
            if versions:
                return versions[0]["name"].split("/")[-1]
        else:
            self.gcloud("secrets", "create", name, "--replication-policy=automatic",
                        "--labels=" + ",".join(key + "=" + value for key, value in LABELS.items()))
        if data is None and file is None:
            raise DeployError("initial_private_secret_required")
        result = self.gcloud("secrets", "versions", "add", name,
                             "--data-file=" + (str(file) if file else "-"), input_bytes=data)
        return result["name"].split("/")[-1]

    def application(self, private_config, ca_file):
        if not self.state.get("guardUrl") or not self.state.get("budget") or not self.state.get("protectionVerified"):
            raise DeployError("cost_protection_required_before_app")
        image = self.source_image("app")
        config_name = "futurecode-config"
        ca_name = "futurecode-mysql-ca"
        known = self.state.get("secrets", {})
        if config_name not in known:
            java = shutil.which("java")
            if not java:
                raise DeployError("java_required_for_private_configuration")
            before = hashlib.sha256(Path(private_config).read_bytes()).digest()
            result = subprocess.run([java, str(ROOT / "ops" / "gcp" / "SecretConfig.java"), str(private_config)],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, cwd=ROOT)
            if result.returncode or not result.stdout or len(result.stdout) > 16384:
                raise DeployError("private_configuration_export_failed")
            config_version = self.secret(config_name, data=result.stdout)
            result.stdout = b""
            if hashlib.sha256(Path(private_config).read_bytes()).digest() != before:
                raise DeployError("local_credentials_changed_unexpectedly")
        else:
            config_version = known[config_name]
        ca_version = self.secret(ca_name, file=Path(ca_file))
        self.state["secrets"] = {config_name: config_version, ca_name: ca_version}
        self.save()
        for name in (config_name, ca_name):
            self.gcloud("secrets", "add-iam-policy-binding", name,
                        "--member=serviceAccount:" + self.app_identity, "--role=roles/secretmanager.secretAccessor")
        variables = {
            "APP_ENV": "production", "HOST": "0.0.0.0",
            "APP_ORIGIN": self.state.get("appUrl") or f"https://{self.service}-{self.project_number}.{self.region}.run.app",
            "DATABASE_SCHEMA": "FutureTool", "MYSQL_TLS": "required",
            "MYSQL_CA_FILE": "/secrets/mysql/mysql-ca.pem", "MYSQL_CONNECTION_LIMIT": "4",
            "SPRING_CONFIG_ADDITIONAL_LOCATION": "file:/secrets/config/futurecode.properties",
            "EXECUTION_ENABLED": "false", "PAYMENTS_ENABLED": "false",
            "JAVA_TOOL_OPTIONS": "-Xms32m -Xmx160m -Xss512k"
        }
        url = self.service_deploy(self.service, image, self.app_identity, variables, secrets={
            "/secrets/config/futurecode.properties": (config_name, config_version),
            "/secrets/mysql/mysql-ca.pem": (ca_name, ca_version)
        })
        if url != variables["APP_ORIGIN"]:
            variables["APP_ORIGIN"] = url
            self.service_deploy(self.service, image, self.app_identity, variables, secrets={
                "/secrets/config/futurecode.properties": (config_name, config_version),
                "/secrets/mysql/mysql-ca.pem": (ca_name, ca_version)
            })
        self.state["appUrl"] = url
        self.save()
        self.gcloud("run", "services", "add-iam-policy-binding", self.service,
                    "--region=" + self.region, "--member=allUsers", "--role=roles/run.invoker")
        return {"project": self.project, "appUrl": url, "productionConfigurationMounted": True,
                "credentialsPrinted": False, "executionEnabled": False, "paymentsEnabled": False}

    def verify_protection(self):
        if not self.state.get("budget") or not self.state.get("guardUrl"):
            raise DeployError("protection_not_provisioned")
        budget = self.api("GET", "https://billingbudgets.googleapis.com/v1/" + self.state["budget"])
        filters = budget.get("budgetFilter", {})
        if (filters.get("projects") != ["projects/" + self.project_number] or
            filters.get("creditTypesTreatment") != "INCLUDE_ALL_CREDITS" or
            filters.get("calendarPeriod") != "MONTH" or filters.get("services") or
            budget.get("spendCap") or
            budget.get("amount", {}).get("specifiedAmount", {}).get("currencyCode") != "INR" or
            str(budget.get("amount", {}).get("specifiedAmount", {}).get("units")) != "5" or
            [row.get("thresholdPercent") for row in budget.get("thresholdRules", [])] != [.2, .4, .6, .8, 1.0]):
            raise DeployError("budget_readback_failed")
        rules = budget.get("notificationsRule", {})
        if rules.get("pubsubTopic") != self.topic or self.state["notificationChannel"] not in rules.get("monitoringNotificationChannels", []):
            raise DeployError("budget_notification_readback_failed")
        subscription = self.api("GET", "https://pubsub.googleapis.com/v1/" + self.subscription)
        push = subscription.get("pushConfig", {})
        if (subscription.get("topic") != self.topic or push.get("pushEndpoint") != self.state["guardUrl"] or
            push.get("oidcToken", {}).get("serviceAccountEmail") != self.push_identity or
            push.get("oidcToken", {}).get("audience") != self.state["guardUrl"]):
            raise DeployError("authenticated_guard_delivery_not_configured")
        topic_policy = self.gcloud("pubsub", "topics", "get-iam-policy", self.topic)
        if not any(row.get("role") == "roles/pubsub.publisher" and
                   "serviceAccount:billing-budget-alert@system.gserviceaccount.com" in row.get("members", [])
                   for row in topic_policy.get("bindings", [])):
            raise DeployError("google_budget_publisher_not_authorized")
        service = self.gcloud("run", "services", "describe", self.guard_service, "--region=" + self.region)
        self.verify_service_profile(service, self.guard_identity)
        policy = self.gcloud("run", "services", "get-iam-policy", self.guard_service, "--region=" + self.region)
        if any(member in {"allUsers", "allAuthenticatedUsers"}
               for row in policy.get("bindings", []) for member in row.get("members", [])):
            raise DeployError("cost_guard_is_not_private")
        unauthenticated = self.session.get(self.state["guardUrl"], timeout=(10, 60), allow_redirects=False)
        if unauthenticated.status_code != 403:
            raise DeployError("private_guard_did_not_reject_anonymous_access")
        started = datetime.now(timezone.utc).isoformat()
        self.publish_check("readiness-check")
        self.publish_check("quota-check")
        observed = set()
        for _ in range(25):
            query = (
                f'logName="projects/{self.project}/logs/futurecode-cost-guard" '
                f'AND timestamp>="{started}" AND '
                '(jsonPayload.event="futurecode.guard_check" OR jsonPayload.event="futurecode.guard_failure")'
            )
            rows = self.gcloud("logging", "read", query, "--limit=20", "--order=desc")
            failures = [row.get("jsonPayload", {}).get("code") for row in rows
                        if row.get("jsonPayload", {}).get("event") == "futurecode.guard_failure"]
            if failures:
                code = failures[0] if re.fullmatch(r"[a-z_]{1,80}", str(failures[0])) else "guardian_runtime_failed"
                raise DeployError("guardian_probe_" + code)
            observed.update(row.get("jsonPayload", {}).get("action") for row in rows)
            if {"permissions_verified", "quota_checked"} <= observed:
                self.state["protectionVerified"] = datetime.now(timezone.utc).isoformat()
                self.save()
                return {
                    "project": self.project, "projectScopedBudgetVerified": True,
                    "spendAlerts": POLICY["spend_alerts"], "privateGuardVerified": True,
                    "authenticatedPubsubProbeVerified": True, "billingUnlinkPermissionVerified": True,
                    "liveQuotaReadAndStateWriteVerified": True,
                    "emergencyShutdownActuallyTriggered": False,
                    "finalInvoiceCapGuaranteed": False
                }
            time.sleep(5)
        raise DeployError("guardian_live_probe_not_observed")

    def verify_service_profile(self, service, identity):
        self.owned(service.get("metadata", {}))
        annotations = service.get("metadata", {}).get("annotations", {})
        template = service.get("spec", {}).get("template", {})
        revision_annotations = template.get("metadata", {}).get("annotations", {})
        spec = template.get("spec", {})
        containers = spec.get("containers", [])
        if len(containers) != 1:
            raise DeployError("unexpected_container_count")
        limits = containers[0].get("resources", {}).get("limits", {})
        if (str(limits.get("cpu")) not in {"1", "1000m"} or limits.get("memory") != "512Mi" or
            spec.get("serviceAccountName") != identity or
            str(annotations.get("run.googleapis.com/maxScale")) != "1" or
            revision_annotations.get("run.googleapis.com/cpu-throttling") != "true" or
            revision_annotations.get("run.googleapis.com/startup-cpu-boost") != "false"):
            raise DeployError("fixed_free_usage_service_profile_changed")
        return containers[0]

    def verify(self):
        result = self.verify_protection()
        url = self.state.get("appUrl")
        if not url:
            return result
        service = self.gcloud("run", "services", "describe", self.service, "--region=" + self.region)
        container = self.verify_service_profile(service, self.app_identity)
        environment = {row["name"]: row.get("value") for row in container.get("env", [])}
        if environment.get("APP_ORIGIN") != url or environment.get("APP_ENV") != "production":
            raise DeployError("production_origin_readback_failed")
        if environment.get("EXECUTION_ENABLED") != "false" or environment.get("PAYMENTS_ENABLED") != "false":
            raise DeployError("unapproved_external_integrations_enabled")
        probe = container.get("startupProbe", {})
        if (probe.get("httpGet", {}).get("path") != "/readyz" or
            probe.get("httpGet", {}).get("httpHeaders") != [{"name": "Host", "value": urlparse(url).netloc}]):
            raise DeployError("application_readiness_startup_probe_missing")
        for route in ("/readyz", "/", "/privacy", "/api/catalog"):
            response = self.session.get(url + route, timeout=(10, 60), allow_redirects=False)
            if response.status_code != 200:
                raise DeployError("public_application_readiness_failed")
        for route in ("/.env", "/.git/config", "/certs/mysql-ca.pem"):
            if self.session.get(url + route, timeout=(10, 60), allow_redirects=False).status_code != 404:
                raise DeployError("private_path_exposed")
        result.update({"appUrl": url, "publicApplicationReady": True, "productionOriginVerified": True,
                       "privatePathsBlocked": True, "externalIntegrationsDisabled": True})
        return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True)
    parser.add_argument("--region", default="us-central1")
    parser.add_argument("--phase", choices=["plan", "protect", "app", "verify"], default="plan")
    parser.add_argument("--state-file")
    parser.add_argument("--private-config", default=str(ROOT / ".env"))
    parser.add_argument("--mysql-ca", default=str(ROOT / "certs" / "mysql-ca.pem"))
    parser.add_argument("--accept-estimated-target-risk", action="store_true")
    args = parser.parse_args()
    if args.phase != "plan" and not args.accept_estimated_target_risk:
        raise DeployError("explicit_estimated_target_risk_acceptance_required")
    deployment = Deployment(args.project, args.region, args.state_file)
    plan = deployment.preflight()
    if args.phase == "plan":
        print(json.dumps(plan))
    elif args.phase == "protect":
        print(json.dumps(deployment.protect()))
    elif args.phase == "app":
        print(json.dumps(deployment.application(args.private_config, args.mysql_ca)))
    else:
        print(json.dumps(deployment.verify()))


if __name__ == "__main__":
    try:
        main()
    except (DeployError, subprocess.SubprocessError, OSError) as error:
        code = str(error) if isinstance(error, DeployError) else "local_deployment_tool_failed"
        print(json.dumps({"ok": False, "code": code}))
        sys.exit(1)
