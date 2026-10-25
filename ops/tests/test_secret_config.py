import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


@unittest.skipUnless(shutil.which("java"), "Java is required for the repository-owned secret-export tool.")
class SecretConfigTest(unittest.TestCase):
    def test_only_needed_configuration_is_forwarded_and_cloud_sessions_use_a_fresh_secret(self):
        source = Path(__file__).resolve().parents[1] / "gcp" / "SecretConfig.java"
        data = (
            "DATABASE_URL=mysql://fixture:fixture@localhost/FutureTool\n"
            "GOOGLE_CLIENT_ID=fixture.apps.googleusercontent.com\n"
            "GOOGLE_CLIENT_SECRET=synthetic-fixture-not-live\n"
            "SESSION_SECRET=local-only-fixture\n"
            "UNRELATED_SECRET=do-not-upload-this-fixture\n"
        )
        with tempfile.TemporaryDirectory(prefix="futurecode-secret-fixture-") as directory:
            file = Path(directory) / ".env"
            file.write_text(data, "utf-8")
            result = subprocess.run(["java", str(source), str(file)], capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0)
            text = result.stdout.decode()
            self.assertIn("DATABASE_URL=", text)
            self.assertIn("GOOGLE_CLIENT_ID=", text)
            self.assertIn("GOOGLE_CLIENT_SECRET=", text)
            self.assertRegex(text, r"SESSION_SECRET=[a-f0-9]{64}")
            self.assertNotIn("local-only-fixture", text)
            self.assertNotIn("UNRELATED_SECRET", text)
            self.assertNotIn("do-not-upload", text)
            self.assertEqual(file.read_text("utf-8"), data)

    def test_incomplete_private_configuration_fails_without_echoing_values(self):
        source = Path(__file__).resolve().parents[1] / "gcp" / "SecretConfig.java"
        with tempfile.TemporaryDirectory(prefix="futurecode-secret-fixture-") as directory:
            file = Path(directory) / ".env"
            file.write_text("DATABASE_URL=synthetic-must-not-appear\n", "utf-8")
            result = subprocess.run(["java", str(source), str(file)], capture_output=True, timeout=30)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, b"")
            self.assertNotIn(b"synthetic-must-not-appear", result.stderr)


if __name__ == "__main__":
    unittest.main()
