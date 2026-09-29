#!/usr/bin/env bash
# Runs as root via SSM. No secret values or application logs are printed.
set -Eeuo pipefail
export PATH="/snap/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
export AWS_PAGER=''
umask 077

revision=${1:?Commit SHA required}
image=${2:?ECR digest reference required}
region=${3:?AWS region required}
[[ $revision =~ ^[0-9a-f]{40}$ ]]
[[ $region =~ ^[a-z]{2}-[a-z]+-[0-9]+$ ]]
[[ $image =~ ^[0-9]{12}\.dkr\.ecr\.$region\.amazonaws\.com/[a-z0-9]+([._/-][a-z0-9]+)*@sha256:[0-9a-f]{64}$ ]]
bundle=$(cd "$(dirname "$0")" && pwd)
root=/opt/vium
legacy=/home/ubuntu/vium-releases/3fb5582
mkdir -p "$root/releases"
exec 9>"$root/deploy.lock"
flock -n 9 || { echo 'Another deployment is still running.' >&2; exit 1; }

# Check both filesystems: Docker may use a separate data disk.
docker_root=$(docker info --format '{{.DockerRootDir}}')
for directory in "$root" "$docker_root"; do
  available_kib=$(df -Pk "$directory" | awk 'END {print $4}')
  [[ $available_kib =~ ^[0-9]+$ ]]
  if (( available_kib < 2 * 1024 * 1024 )); then
    echo "Less than 2 GiB free on $directory; clean unused releases/images before deploying." >&2
    exit 1
  fi
done

compose() {
  local directory=$1
  shift
  local tag
  tag=$(cat "$directory/revision")
  IMAGE_TAG="$tag" docker compose --project-name vium-prod \
    --project-directory "$directory" --env-file "$directory/.env.prod" \
    -f "$directory/compose.prod.yml" -f "$directory/compose.micro.yml" \
    -f "$directory/compose.image.yml" "$@"
}

health() {
  curl --fail --silent --show-error --max-time 10 \
    http://127.0.0.1:8080/actuator/health |
    python3 -c 'import json,sys; sys.exit(json.load(sys.stdin).get("status") != "UP")'
}

# First CD run imports the existing manual deployment without stopping it.
if [[ ! -L "$root/current" ]]; then
  test ! -e "$root/current"
  test -f "$legacy/.env.prod"
  test -f "$legacy/deploy/certs/global-bundle.pem"
  test -f "$legacy/compose.micro.yml"
  test -f "$legacy/compose.prod.yml"
  health
  old_image=$(docker inspect --format '{{.Image}}' vium-prod-app-1)
  [[ $old_image =~ ^sha256:[0-9a-f]{64}$ ]]
  mkdir -p "$root/shared/certs"
  chmod 755 "$root/shared" "$root/shared/certs"
  # Preserve any files copied during an interrupted initialization.
  if [[ ! -e "$root/shared/.env.prod" ]]; then
    install -m 600 "$legacy/.env.prod" "$root/shared/.env.prod"
  fi
  if [[ ! -e "$root/shared/certs/global-bundle.pem" ]]; then
    install -m 644 "$legacy/deploy/certs/global-bundle.pem" "$root/shared/certs/global-bundle.pem"
  fi
  baseline=$(mktemp -d "$root/releases/bootstrap.XXXXXX")
  cp "$legacy/compose.prod.yml" "$legacy/compose.micro.yml" "$baseline/"
  mkdir "$baseline/deploy"
  ln -s "$root/shared/certs" "$baseline/deploy/certs"
  ln -s "$root/shared/.env.prod" "$baseline/.env.prod"
  printf '%s\n' '3fb558204c2d7ece23db64d8c6af1987666a6c1d' > "$baseline/revision"
  printf 'services:\n  app:\n    image: "%s"\n' "$old_image" > "$baseline/compose.image.yml"
  compose "$baseline" config --quiet
  touch "$baseline/succeeded"
  ln -s "$baseline" "$root/current"
fi

previous=$(readlink -f "$root/current")
test -d "$previous"
previous_healthy=true
if ! health; then
  previous_healthy=false
  echo 'Previous application is unhealthy; continuing with the repair deployment.' >&2
fi
compose "$previous" config --quiet

# Docker credentials only live in a temporary directory for this invocation.
export DOCKER_CONFIG
DOCKER_CONFIG=$(mktemp -d)
trap 'rm -rf -- "$DOCKER_CONFIG"' EXIT
registry=${image%%/*}
aws ecr get-login-password --region "$region" |
  docker login --username AWS --password-stdin "$registry"
docker pull "$image"

release=$(mktemp -d "$root/releases/$revision.XXXXXX")
cp "$bundle/compose.prod.yml" "$bundle/compose.micro.yml" "$release/"
mkdir "$release/deploy"
ln -s "$root/shared/certs" "$release/deploy/certs"
ln -s "$root/shared/.env.prod" "$release/.env.prod"
printf '%s\n' "$revision" > "$release/revision"
printf 'services:\n  app:\n    image: "%s"\n' "$image" > "$release/compose.image.yml"
compose "$release" config --quiet

rollback() {
  trap - ERR
  echo 'Deployment failed; restoring previous application (database is unchanged).' >&2
  if [[ $previous_healthy == false ]]; then
    echo 'Previous application was already unhealthy before this deployment; rollback may not restore service.' >&2
  fi
  if compose "$previous" up -d --no-build --wait --wait-timeout 240 && health; then
    echo 'Previous application restored.' >&2
  else
    echo "ROLLBACK FAILED. Inspect the server directly. Previous configuration: $previous" >&2
  fi
  exit 1
}
trap rollback ERR
compose "$release" up -d --no-build --wait --wait-timeout 240
health
touch "$release/succeeded"
ln -sfn "$release" "$root/current.next"
mv -Tf "$root/current.next" "$root/current"
trap - ERR
printf 'Deployed %s\nImage %s\nConfiguration %s\n' "$revision" "$image" "$release"
# Keep previous release directories and images for manual rollback; do not prune here.
