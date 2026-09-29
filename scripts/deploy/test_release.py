import base64
import io
import json
from pathlib import Path
import subprocess
import tarfile
import unittest
from unittest.mock import patch

import release


class ReleaseTests(unittest.TestCase):
    def test_configuration_rejects_shell_injection(self):
        with self.assertRaises(ValueError):
            release.validate("a" * 40, "ap-northeast-2", "vium-be;id", "i-032304a35af9004e9")

    def test_payload_only_contains_deployment_files(self):
        root = Path(__file__).resolve().parents[2]
        with patch("release.Path", side_effect=lambda name: root / name):
            payload = release.command_payload("a" * 40, "example@sha256:" + "b" * 64, "ap-northeast-2")
        command = payload["commands"][0]
        encoded = command.split("printf '%s' '")[1].split("'")[0]
        with tarfile.open(fileobj=io.BytesIO(base64.b64decode(encoded))) as archive:
            self.assertEqual(set(archive.getnames()), {"remote.sh", "compose.prod.yml", "compose.micro.yml"})

    def test_access_denied_is_not_treated_as_missing_image(self):
        error = subprocess.CalledProcessError(1, "aws", stderr="(AccessDeniedException)")
        with patch("release.aws", side_effect=error):
            with self.assertRaises(subprocess.CalledProcessError):
                release.image_digest("vium-be", "a" * 40)

    def test_wait_handles_eventual_consistency_then_success(self):
        missing = subprocess.CalledProcessError(1, "aws", stderr="(InvocationDoesNotExist)")
        with patch("release.aws", side_effect=[missing, json.dumps({"Status": "InProgress"}),
                                               json.dumps({"Status": "Success"})]), patch("release.time.sleep"):
            release.wait_for_command("command", "instance")

    def test_remote_failure_fails_cd_without_printing_server_secrets(self):
        with patch("release.aws", return_value=json.dumps({"Status": "Failed", "StandardOutputContent": "secret"})):
            with self.assertRaisesRegex(RuntimeError, "deployment Failed") as caught:
                release.wait_for_command("command", "instance")
            self.assertNotIn("secret", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
