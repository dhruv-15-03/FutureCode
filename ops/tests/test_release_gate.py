import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class ReleaseGateTest(unittest.TestCase):
    def test_checked_in_ci_is_non_deploying_and_has_no_cloud_credentials(self):
        text = (ROOT / ".github" / "workflows" / "verify.yml").read_text("utf-8")
        self.assertIn("contents: read", text)
        self.assertIn("python -m unittest discover -s ops/tests", text)
        self.assertIn("npm run check", text)
        self.assertIn("./mvnw -B -q verify", text)
        self.assertIn("npm run test:browser", text)
        self.assertIn("runs-on: ubuntu-latest", text)
        self.assertIn("docker build --tag futurecode-app-verify", text)
        self.assertIn("Smoke-test production runtime without credentials", text)
        for forbidden in ("id-token: write", "secrets.", "gcloud ", "deploy.py", "google-github-actions", "pull_request_target",
                          "docker push", "docker login"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, text)

    def test_runtime_image_is_built_after_java_tests_but_contains_no_test_classes(self):
        text = (ROOT / "Dockerfile").read_text("utf-8")
        self.assertIn("COPY src/test ./src/test", text)
        self.assertIn("RUN mvn -B -q package", text)
        self.assertNotIn("skipTests", text)
        self.assertNotIn("maven.test.skip", text)
        runtime = text.split("FROM eclipse-temurin:21-jre-jammy", 1)[1]
        self.assertIn("/workspace/target/futurecode.jar", runtime)
        self.assertNotIn("src/test", runtime)
        self.assertNotIn("COPY . ", text)

    def test_source_publication_keeps_cloud_writes_disabled(self):
        self.assertIs(json.loads((ROOT / "ops" / "gcp" / "project.json").read_text("utf-8"))["cloud_changes_enabled"], False)

    def test_deployment_does_not_silently_activate_registry_deletion(self):
        text = (ROOT / "ops" / "gcp" / "deploy.py").read_text("utf-8")
        self.assertIn('"--dry-run"', text)
        self.assertNotIn('"--no-dry-run"', text)


if __name__ == "__main__":
    unittest.main()
