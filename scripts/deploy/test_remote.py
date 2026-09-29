"""Exercise server transitions in temporary directories with mocked Docker/AWS."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(sys.platform == "linux", "Server script requires Linux coreutils/flock")
class RemoteTests(unittest.TestCase):
    def run_deployment(self, failure):
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
        script = Path(__file__).with_name("remote.sh").read_text()
        script = script.replace("root=/opt/vium", f"root={root}")
        script = script.replace("legacy=/home/ubuntu/vium-releases/3fb5582", f"legacy={legacy}")
        script = script.replace('export PATH="', f'export PATH="{binaries}:')
        (bundle / "remote.sh").write_text(script)
        mock = f'''#!{sys.executable}
import json, os, pathlib, sys
args = sys.argv[1:]
name = pathlib.Path(sys.argv[0]).name
with open(os.environ["MOCK_LOG"], "a") as log:
    log.write(json.dumps([name, *args]) + "\\n")
if name == "curl":
    print('{{"status":"UP"}}')
elif name == "aws":
    print("mock-token")
elif args[0] == "inspect":
    print("sha256:" + "b" * 64)
elif args[0] == "login":
    sys.stdin.read()
elif args[0] == "pull" and os.environ["MOCK_FAIL"] == "pull":
    sys.exit(1)
elif args[0] == "compose" and "up" in args:
    directory = args[args.index("--project-directory") + 1]
    if "bootstrap." not in directory and os.environ["MOCK_FAIL"] == "up":
        sys.exit(1)
'''
        for name in ("docker", "aws", "curl"):
            executable = binaries / name
            executable.write_text(mock)
            executable.chmod(0o755)
        log = base / "commands"
        env = dict(os.environ, PATH=f"{binaries}:{os.environ['PATH']}",
                   MOCK_LOG=str(log), MOCK_FAIL=failure)
        result = subprocess.run(["bash", str(bundle / "remote.sh"), "a" * 40,
                                 "243919538384.dkr.ecr.ap-northeast-2.amazonaws.com/vium-be@sha256:" + "c" * 64,
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
