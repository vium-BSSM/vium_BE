"""Exercise server transitions in temporary directories with mocked Docker/AWS."""
import json
import os
import shutil
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(sys.platform == "linux", "Server script requires Linux coreutils/flock")
class RemoteTests(unittest.TestCase):
    def run_deployment(self, failure, repository="vium-be"):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        base = Path(temp.name)
        root, legacy, bundle, binaries = [base / name for name in ("root", "legacy", "bundle", "bin")]
        for path in (legacy / "deploy/certs", bundle, binaries):
            path.mkdir(parents=True)
        (legacy / ".env.prod").write_text("DB_PASSWORD=do-not-print-this\n")
        (legacy / "deploy/certs/global-bundle.pem").write_text("certificate")
        for name in ("compose.prod.yml", "compose.micro.yml"):
            (legacy / name).write_text("services: {}\n")
            (bundle / name).write_text("services: {}\n")
        if failure.startswith("unhealthy"):
            baseline = root / "releases/bootstrap.existing"
            shutil.copytree(legacy, baseline)
            (baseline / "revision").write_text("b" * 40)
            (baseline / "compose.image.yml").write_text("services: {}\n")
            shutil.copytree(legacy / "deploy/certs", root / "shared/certs")
            shutil.copy(legacy / ".env.prod", root / "shared/.env.prod")
            (root / "current").symlink_to(baseline)
        script = Path(__file__).with_name("remote.sh").read_text()
        script = script.replace("root=/opt/vium", f"root={root}")
        script = script.replace("legacy=/home/ubuntu/vium-releases/3fb5582", f"legacy={legacy}")
        script = script.replace('export PATH="', f'export PATH="{binaries}:')
        (bundle / "remote.sh").write_text(script)
        mock = f'''#!{sys.executable}
import json, os, pathlib, sys
args = sys.argv[1:]
name = pathlib.Path(sys.argv[0]).name
state = pathlib.Path(os.environ["MOCK_LOG"] + ".state")
mode = os.environ["MOCK_FAIL"]
application = state.read_text() if state.exists() else "old"
with open(os.environ["MOCK_LOG"], "a") as log:
    log.write(json.dumps([name, *args]) + "\\n")
if name == "curl":
    down = (mode.startswith("unhealthy") and application == "old") or (mode == "health" and application == "new")
    print(json.dumps({{"status": "DOWN" if down else "UP"}}))
elif name == "df":
    print("Filesystem 1024-blocks Used Available Capacity Mounted")
    print("mock 10000000 1 " + ("1024" if mode == "disk" else "9000000") + " 1% /")
elif name == "aws":
    print("mock-token")
elif args[0] == "info":
    print("/tmp")
elif args[0] == "inspect":
    print("sha256:" + "b" * 64)
elif args[0] == "login":
    sys.stdin.read()
elif args[0] == "pull" and os.environ["MOCK_FAIL"] == "pull":
    sys.exit(1)
elif args[0] == "compose" and "up" in args:
    directory = args[args.index("--project-directory") + 1]
    state.write_text("old" if "bootstrap." in directory else "new")
    if "bootstrap." not in directory and mode in {{"up", "unhealthy-up"}}:
        sys.exit(1)
'''
        for name in ("docker", "aws", "curl", "df"):
            executable = binaries / name
            executable.write_text(mock)
            executable.chmod(0o755)
        log = base / "commands"
        env = dict(os.environ, PATH=f"{binaries}:{os.environ['PATH']}",
                   MOCK_LOG=str(log), MOCK_FAIL=failure)
        result = subprocess.run(["bash", str(bundle / "remote.sh"), "a" * 40,
                                 f"243919538384.dkr.ecr.ap-northeast-2.amazonaws.com/{repository}@sha256:" + "c" * 64,
                                 "ap-northeast-2"], env=env, text=True, capture_output=True)
        self.assertNotIn("do-not-print-this", result.stdout + result.stderr)
        commands = [json.loads(line) for line in log.read_text().splitlines()]
        return root, result, commands

    def test_success_switches_current_and_preserves_secrets(self):
        root, result, _ = self.run_deployment("")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((root / "current/revision").read_text().strip(), "a" * 40)
        self.assertEqual((root / "shared/.env.prod").stat().st_mode & 0o777, 0o600)

    def test_failed_up_restores_bootstrap_and_returns_failure(self):
        root, result, commands = self.run_deployment("up")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("bootstrap.", os.readlink(root / "current"))
        up = [command for command in commands if "compose" in command and "up" in command]
        self.assertEqual(len(up), 2)
        self.assertIn("bootstrap.", up[-1][up[-1].index("--project-directory") + 1])
        self.assertIn("Previous application restored", result.stderr)

    def test_pull_failure_never_recreates_container(self):
        _, result, commands = self.run_deployment("pull")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any("compose" in command and "up" in command for command in commands))

    def test_health_failure_after_successful_up_rolls_back(self):
        root, result, commands = self.run_deployment("health")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("bootstrap.", os.readlink(root / "current"))
        self.assertEqual(sum("compose" in cmd and "up" in cmd for cmd in commands), 2)
        self.assertIn("Previous application restored", result.stderr)

    def test_unhealthy_existing_app_can_be_repaired(self):
        root, result, _ = self.run_deployment("unhealthy")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((root / "current/revision").read_text().strip(), "a" * 40)
        self.assertIn("continuing with the repair", result.stderr)

    def test_failed_repair_reports_previous_app_was_unhealthy(self):
        root, result, _ = self.run_deployment("unhealthy-up")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("bootstrap.", os.readlink(root / "current"))
        self.assertIn("already unhealthy before", result.stderr)
        self.assertIn("ROLLBACK FAILED", result.stderr)

    def test_low_disk_stops_before_pull_or_up(self):
        _, result, commands = self.run_deployment("disk")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Less than 2 GiB", result.stderr)
        self.assertFalse(any("pull" in cmd or "up" in cmd for cmd in commands))

    def test_dotted_repository_name_is_supported(self):
        _, result, _ = self.run_deployment("", repository="team/vium.be")
        self.assertEqual(result.returncode, 0, result.stderr)
