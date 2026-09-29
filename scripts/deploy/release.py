"""Publish an immutable commit image and transport deployment files through SSM."""
import base64
import io
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import time
import urllib.request


def aws(*args):
    return subprocess.run(
        ["aws", *args, "--output", "json", "--no-cli-pager"],
        check=True, text=True, capture_output=True, timeout=90,
    ).stdout


def validate(sha, region, repository, instance):
    for value, pattern in (
        (sha, r"[0-9a-f]{40}"),
        (region, r"[a-z]{2}-[a-z]+-[0-9]+"),
        (repository, r"[a-z0-9]+(?:[._/-][a-z0-9]+)*"),
        (instance, r"i-[0-9a-f]{17}"),
    ):
        if not re.fullmatch(pattern, value):
            raise ValueError("Invalid deployment configuration")


def image_digest(repository, sha):
    try:
        result = json.loads(aws("ecr", "describe-images", "--repository-name", repository,
                                "--image-ids", f"imageTag={sha}"))
    except subprocess.CalledProcessError as error:
        if "(ImageNotFoundException)" in error.stderr:
            return None
        raise
    digest = result["imageDetails"][0]["imageDigest"]
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise ValueError("Invalid ECR digest")
    return digest


def command_payload(sha, image, region):
    archive = io.BytesIO()
    # Explicit allowlist: never send environment files or credentials to SSM.
    with tarfile.open(fileobj=archive, mode="w:gz") as tar:
        for source, name in (
            ("scripts/deploy/remote.sh", "remote.sh"),
            ("compose.prod.yml", "compose.prod.yml"),
            ("compose.micro.yml", "compose.micro.yml"),
        ):
            content = Path(source).read_bytes()
            info = tarfile.TarInfo(name)
            info.size = len(content)
            info.mode = 0o600
            tar.addfile(info, io.BytesIO(content))
    encoded = base64.b64encode(archive.getvalue()).decode("ascii")
    command = "\n".join([
        "set -eu",
        "umask 077",
        'bundle=$(mktemp -d /tmp/vium-deploy.XXXXXX)',
        "trap 'rm -rf -- \"$bundle\"' EXIT",
        f"printf '%s' '{encoded}' | base64 -d | tar -xz -C \"$bundle\"",
        'bash "$bundle/remote.sh" ' + " ".join(map(shlex.quote, (sha, image, region))),
    ])
    if len(command.encode()) > 24000:
        raise ValueError("Deployment bundle exceeds SSM command size budget")
    return {"commands": [command], "executionTimeout": ["1200"]}


def wait_for_command(command_id, instance, timeout=1500):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = json.loads(aws("ssm", "get-command-invocation", "--command-id", command_id,
                                    "--instance-id", instance))
        except subprocess.CalledProcessError as error:
            # SSM invocation records are eventually consistent after SendCommand.
            if "(InvocationDoesNotExist)" not in error.stderr:
                raise
        else:
            status = result["Status"]
            if status == "Success":
                print("SSM deployment succeeded.", flush=True)
                return
            if status not in {"Pending", "InProgress", "Delayed"}:
                # Do not forward arbitrary server output, which could contain secrets.
                raise RuntimeError(f"SSM deployment {status}; inspect command {command_id} in AWS")
        time.sleep(10)
    raise TimeoutError(f"SSM result timed out; inspect {command_id} before retrying. It may still run.")


def main():
    sha = os.environ["GITHUB_SHA"]
    region = os.environ["AWS_REGION"]
    repository = os.environ["ECR_REPOSITORY"]
    instance = os.environ["EC2_INSTANCE_ID"]
    validate(sha, region, repository, instance)

    # An old run must not roll production back after a newer commit was merged.
    request = urllib.request.Request(
        f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}/git/ref/heads/main",
        headers={"Authorization": f"Bearer {os.environ['GH_TOKEN']}",
                 "Accept": "application/vnd.github+json"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        latest = json.load(response)["object"]["sha"]
    if latest != sha:
        print("A newer main commit exists; skipping this stale deployment.")
        return

    account = json.loads(aws("sts", "get-caller-identity"))["Account"]
    if not re.fullmatch(r"[0-9]{12}", account):
        raise ValueError("Invalid AWS account")
    registry = f"{account}.dkr.ecr.{region}.amazonaws.com"
    digest = image_digest(repository, sha)
    if digest is None:
        token = subprocess.run(
            ["aws", "ecr", "get-login-password", "--region", region],
            check=True, text=True, capture_output=True, timeout=90,
        ).stdout
        subprocess.run(["docker", "login", "--username", "AWS", "--password-stdin", registry],
                       input=token, text=True, check=True, timeout=90)
        try:
            target = f"{registry}/{repository}:{sha}"
            subprocess.run(["docker", "tag", f"vium-be:{sha}", target], check=True)
            subprocess.run(["docker", "push", target], check=True, timeout=600)
        finally:
            subprocess.run(["docker", "logout", registry], check=False)
        digest = image_digest(repository, sha)
        if digest is None:
            raise RuntimeError("Pushed image was not found in ECR")
    image = f"{registry}/{repository}@{digest}"
    parameters = command_payload(sha, image, region)
    result = json.loads(aws("ssm", "send-command", "--document-name", "AWS-RunShellScript",
                            "--instance-ids", instance, "--timeout-seconds", "120",
                            "--comment", f"Vium deployment {sha}",
                            "--parameters", json.dumps(parameters)))
    command_id = result["Command"]["CommandId"]
    print(f"Image: {image}\nSSM command: {command_id}", flush=True)
    wait_for_command(command_id, instance)


if __name__ == "__main__":
    main()
