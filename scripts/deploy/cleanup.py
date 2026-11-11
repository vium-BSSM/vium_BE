"""Conservative post-deployment cleanup; caller must hold deploy.lock."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

KEEP_DISTINCT_IMAGES = 3
LOW_DISK_KIB = 4 * 1024 * 1024
SHA_RELEASE = re.compile(r"[0-9a-f]{40}\.[A-Za-z0-9]{6}")
BOOTSTRAP_RELEASE = re.compile(r"bootstrap\.[A-Za-z0-9]{6}")
REPOSITORY = re.compile(r"[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/[a-z0-9]+(?:[._/-][a-z0-9]+)*")
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}")


@dataclass(frozen=True)
class Release:
    name: str
    path: Path
    kind: str
    succeeded_at: float | None
    image: str | None


@dataclass(frozen=True)
class LocalImage:
    id: str
    repo_digests: tuple
    repo_tags: tuple


@dataclass(frozen=True)
class Plan:
    keep: list[Release]
    delete: list[Release]
    unknown: list[Release]


def select_releases(releases, current, previous, keep_images=KEEP_DISTINCT_IMAGES):
    known = {r.path: r for r in releases if r.kind in {"sha", "bootstrap"}}
    if current not in known or previous not in known or keep_images < 1:
        raise ValueError("Invalid protected release")
    keep = {current, previous} | {r.path for r in known.values() if r.kind == "bootstrap"}
    images = set()
    successful = sorted((r for r in known.values() if r.kind == "sha" and r.succeeded_at is not None),
                        key=lambda r: (r.succeeded_at, r.name), reverse=True)
    for release in successful:
        if len(images) >= keep_images:
            break
        # Without this reference we cannot determine the recent distinct-image window.
        if release.image is None:
            raise ValueError("Unreadable recent release image")
        keep.add(release.path)
        images.add(release.image)
    return Plan([r for r in releases if r.path in keep],
                [r for r in releases if r.kind == "sha" and r.path not in keep],
                [r for r in releases if r.kind == "unknown"])


def select_images(images, repository, keep_ids, in_use_ids):
    delete, skipped = [], []
    for image in images:
        refs = image.repo_digests + image.repo_tags
        matches = [ref.startswith(repository + "@") or ref.startswith(repository + ":") for ref in refs]
        if image.id in keep_ids or image.id in in_use_ids or not any(matches):
            continue
        (delete if all(matches) else skipped).append(image.id)
    return delete, skipped


def parse_image_reference(text):
    matches = re.findall(r'^    image: "([^"\r\n]+)"$', text, re.MULTILINE)
    if len(matches) != 1:
        raise ValueError("Invalid image override")
    return matches[0]


def summary_line(fields):
    for key, value in fields.items():
        if not re.fullmatch(r"[a-z_]+", key) or not (
                type(value) is int and value >= 0 or isinstance(value, str) and re.fullmatch(r"[a-z-]+", value)):
            raise ValueError("Invalid summary field")
    return "CLEANUP " + " ".join(f"{key}={value}" for key, value in fields.items())


def scan_releases(releases_dir):
    releases = []
    for path in sorted(releases_dir.iterdir()):
        kind = "unknown"
        if not path.is_symlink() and path.is_dir():
            if SHA_RELEASE.fullmatch(path.name):
                kind = "sha"
            elif BOOTSTRAP_RELEASE.fullmatch(path.name):
                kind = "bootstrap"
        succeeded_at, image = None, None
        if kind != "unknown":
            marker = path / "succeeded"
            if marker.is_symlink() or (marker.exists() and not marker.is_file()):
                raise ValueError("Invalid success marker")
            if marker.is_file():
                succeeded_at = marker.stat().st_mtime
            try:
                image = parse_image_reference((path / "compose.image.yml").read_text())
            except (OSError, ValueError):
                pass
        releases.append(Release(path.name, path, kind, succeeded_at, image))
    return releases


def docker(*args):
    return subprocess.run(["docker", *args], timeout=60, check=True,
                          text=True, capture_output=True).stdout


def checked_id(value):
    if not isinstance(value, str) or not IMAGE_ID.fullmatch(value):
        raise ValueError("Invalid image ID")
    return value


def resolve_image_id(ref):
    return checked_id(docker("image", "inspect", "--format", "{{.Id}}", ref).strip())


def list_local_images():
    ids = sorted(set(docker("image", "ls", "-q", "--no-trunc").split()))
    images = []
    for image_id in ids:
        checked_id(image_id)
        records = json.loads(docker("image", "inspect", image_id))
        if len(records) != 1 or records[0]["Id"] != image_id:
            raise ValueError("Invalid image inspection")
        record = records[0]
        refs = []
        for key in ("RepoDigests", "RepoTags"):
            values = record[key] or []
            if not isinstance(values, list) or not all(isinstance(v, str) for v in values):
                raise ValueError("Invalid image references")
            refs.append(tuple(values))
        images.append(LocalImage(image_id, *refs))
    return images


def container_image_ids():
    containers = docker("ps", "-a", "-q", "--no-trunc").split()
    ids = set()
    for container in containers:
        if not re.fullmatch(r"[0-9a-f]{64}", container):
            raise ValueError("Invalid container ID")
        ids.add(checked_id(docker("inspect", "--format", "{{.Image}}", container).strip()))
    return ids


def remove_release(path, releases_dir, protected):
    if (path.is_symlink() or not path.is_dir() or not SHA_RELEASE.fullmatch(path.name)
            or path.parent.resolve() != releases_dir.resolve()
            or path.resolve() in protected):
        raise ValueError("Unsafe release deletion")
    shutil.rmtree(path)


def remove_image(image_id):
    docker("image", "rm", checked_id(image_id))


def free_kib(paths):
    return min(shutil.disk_usage(path).free // 1024 for path in paths)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "previous", "repository", "docker-root"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    fields = dict(status="ok", dry_run=int(args.dry_run), releases_kept=0, releases_removed=0,
                  releases_unknown=0, images_kept=0, images_removed=0, images_failed=0,
                  images_skipped=0, free_kib_before=0, free_kib_after=0, free_kib_min=LOW_DISK_KIB)
    paths = [Path(args.root), Path(args.docker_root)]
    try:
        fields["free_kib_before"] = free_kib(paths)
        root = Path(args.root).resolve(strict=True)
        releases_dir = root / "releases"
        if releases_dir.is_symlink() or not (root / "current").is_symlink():
            raise ValueError("Invalid release root")
        current = (root / "current").resolve(strict=True)
        previous = Path(args.previous).resolve(strict=True)
        if current.parent != releases_dir or previous.parent != releases_dir:
            raise ValueError("Protected release outside releases")
        plan = select_releases(scan_releases(releases_dir), current, previous)
        fields.update(releases_kept=len(plan.keep), releases_unknown=len(plan.unknown))
        retained = list(plan.keep)
        for release in plan.keep:
            print(f"KEEP release {release.path} (current/previous/bootstrap/recent)")
        for release in plan.delete:
            print(f"DELETE release {release.path} (old or unsuccessful)")
            try:
                if not args.dry_run:
                    remove_release(release.path, releases_dir, {current, previous})
                fields["releases_removed"] += 1
            except Exception:
                # A directory that could not be removed still needs its rollback image.
                retained.append(release)
                fields["releases_kept"] += 1
                fields["status"] = "partial"
                print("WARN: release deletion failed", file=sys.stderr)
        try:
            if not REPOSITORY.fullmatch(args.repository):
                raise ValueError("Invalid repository")
            keep_ids = set()
            for release in retained:
                if release.image is None:
                    raise ValueError("Missing retained image")
                keep_ids.add(resolve_image_id(release.image))
            in_use = container_image_ids()
            images = list_local_images()
            delete, skipped = select_images(images, args.repository, keep_ids, in_use)
            fields.update(images_kept=len(keep_ids | in_use), images_skipped=len(skipped))
            for image_id in skipped:
                print(f"SKIP image {image_id[7:19]} (other repository reference)")
            for image_id in delete:
                print(f"DELETE image {image_id[7:19]}")
                try:
                    if not args.dry_run:
                        remove_image(image_id)
                    fields["images_removed"] += 1
                except Exception:
                    fields["images_failed"] += 1
                    fields["status"] = "partial"
                    print("WARN: image deletion failed", file=sys.stderr)
        except Exception:
            fields.update(status="partial", images_skipped="all")
            print("WARN: images=skipped; image protection could not be verified", file=sys.stderr)
    except Exception:
        fields["status"] = "error"
        print("WARN: cleanup failed; deployment itself succeeded", file=sys.stderr)
    finally:
        try:
            fields["free_kib_after"] = free_kib(paths)
            for path in paths:
                if free_kib([path]) < LOW_DISK_KIB:
                    print(f"WARN: less than 4 GiB free after cleanup on {path}", file=sys.stderr)
        except Exception:
            fields["status"] = "error"
        print(summary_line(fields))
    return 0


if __name__ == "__main__":
    sys.exit(main())
