# ECR / SSM 운영 배포

`.github/workflows/cd.yml`은 main push 또는 main에서의 수동 실행을 처리한다.
Dockerfile의 `test bootJar`가 성공해야 AWS 인증과 배포를 진행한다. 별도 CI
실행 완료를 기다리는 구조가 아니라 CD 자체에서 같은 검증을 수행한다.
PR/develop에는 배포하지 않는다. 대기 중 새 main이 생긴 오래된 실행은 건너뛴다.
동시 배포는 GitHub concurrency와 서버 flock으로 제한하며 실행 중인 배포는
자동 취소하지 않는다. GitHub 대기 실행은 최신 실행으로 교체될 수 있다.

## 준비된 AWS 설정

- 서울 ECR `vium-be`, Immutable, AES-256.
- GitHub OIDC 역할 `vium-github-actions`: ECR 로그인/푸시/DescribeImages,
  `AWS-RunShellScript` 및 운영 EC2 하나에 SendCommand, GetCommandInvocation.
- 신뢰 정책: audience `sts.amazonaws.com`, main의 OIDC subject만 허용.
  GitHub Environment는 현재 사용하지 않는다. 추후 환경을 사용하면 subject와
  환경의 허용 브랜치/승인 정책을 함께 변경해야 한다.
- EC2 `vium-ec2-role`: AmazonSSMManagedInstanceCore 및 해당 저장소 pull 권한.
  SSM Agent online, AWS CLI, Docker Compose, Python 3, curl, flock 필요.
- GitHub repository Variables: `AWS_ROLE_ARN`, `AWS_REGION`,
  `ECR_REPOSITORY`, `EC2_INSTANCE_ID`. 장기 액세스 키는 사용하지 않는다.

OIDC 인증이 실패하면 실제 저장소의 subject 형식도 확인한다. GitHub의 신규
저장소/옵트인 설정에서는 조직·저장소 ID가 subject에 포함될 수 있다. 이때도
와일드카드로 넓히지 않고 실제 main subject와 신뢰 정책을 일치시킨다.

## 첫 CD 실행과 운영 파일

현재 운영 중인 수동 릴리스 `/home/ubuntu/vium-releases/3fb5582`를 자동으로
가져온다. 이 경로의 `.env.prod`, `deploy/certs/global-bundle.pem`, 두 Compose
파일이 있어야 하고 `vium-prod-app-1`과 health가 정상이어야 한다. 조건을
만족하지 않으면 현재 앱을 교체하기 전에 중단한다. Git clone은 필요 없다.

첫 실행은 환경변수와 인증서를 `/opt/vium/shared`에 복사한다. 비밀값을
GitHub 또는 SSM 명령 매개변수로 보내지 않는다. 이후 환경변수 변경은
`/opt/vium/shared/.env.prod`에서 수행한다(권한 600). 초기 폴더 변경은 새
운영 설정에 반영되지 않는다. 인증서는 shared/certs에 있고 파일은 644다.

기존 이미지 ID와 Compose 파일을 bootstrap 릴리스에 저장하고, 새 릴리스는
`/opt/vium/releases/<전체SHA>.<고유값>`에 만든다. 환경변수/인증서는 shared를
참조한다. `/opt/vium/current`는 마지막 성공 릴리스의 심볼릭 링크다.

ECR 태그는 전체 commit SHA이며, 이미 존재하면 덮어쓰지 않고 재사용한다.
재실행에서도 테스트 빌드는 수행한다. 실제 배포는 ECR에서 조회한 digest를
사용한다. 서버는 매번 ECR 로그인 후 명시적으로 pull한다. 임시 Docker 인증
디렉터리는 종료 시 제거한다. `compose.image.yml`로 ECR digest를 덮어쓰므로
기존 compose.prod.yml의 로컬 이미지 형식과 pull_policy: never를 유지한다.
IMAGE_TAG도 각 릴리스 revision 파일을 통해 제공한다.

프로젝트 이름 vium-prod, localhost:8080, micro의 RAM 512 MiB / RAM+swap
768 MiB 제한을 유지한다. Nginx와 RDS 설정은 기존 것을 사용한다.
단일 컨테이너를 교체하므로 배포 중 짧은 요청 중단이 발생할 수 있다.
일반 배포에서 기존 앱이 비정상이면 경고만 기록하고 수정본 배포를 계속한다.
최초 bootstrap만 기존 앱의 정상 health를 요구한다.

## 실패와 복구

새 Compose의 up/health 실패 시 직전 성공 릴리스의 이미지와 두 Compose 파일,
이미지 override로 복구한다. 복구가 성공해도 CD는 실패로 표시한다.
배포 전부터 기존 앱이 비정상이었다면 해당 사실도 출력한다. 이전 설정 복원을
시도하지만 정상 서비스 복구를 보장하지 않으며, 복구 실패를 별도로 표시한다.
DB 마이그레이션은 되돌리지 않는다. 이전 앱과 호환되는 마이그레이션이
필요하며 호환되지 않으면 자동 복구도 실패할 수 있다.

SSM 실행 제한은 1200초, 결과 확인은 최대 1500초다. 강제 취소·프로세스 종료·
호스트 장애는 자동 복구를 보장하지 않는다. timeout이 나면 SSM command ID로
실제 명령 상태를 확인하고 재실행한다. 서버 lock이 남아 실행 중이면 새 배포는
중단한다. GitHub에는 서버 원문 로그를 복사하지 않고 command ID만 기록한다.

SSM 터미널에서 현재 상태를 확인하려면:

```bash
sudo bash -c '
set -eu
cd /opt/vium/current
export IMAGE_TAG="$(cat revision)"
docker compose -p vium-prod --env-file .env.prod \
  -f compose.prod.yml -f compose.micro.yml -f compose.image.yml ps
'
curl --fail --silent --show-error https://api.vium.site/actuator/health
```

수동 복구는 실제 이전 정상 릴리스 경로로 아래 placeholder를 교체한 뒤
SSM 터미널에서 실행한다. 이미지를 삭제하지 않았다면 추가 다운로드가 필요 없다.

```bash
sudo bash <<'ROLLBACK'
set -euo pipefail
exec 9>/opt/vium/deploy.lock
flock -n 9
target=/opt/vium/releases/REPLACE_WITH_PREVIOUS_SUCCESSFUL_DIRECTORY
test -d "$target"
cd "$target"
export IMAGE_TAG="$(cat revision)"
docker compose -p vium-prod --env-file .env.prod \
  -f compose.prod.yml -f compose.micro.yml -f compose.image.yml config --quiet
docker compose -p vium-prod --env-file .env.prod \
  -f compose.prod.yml -f compose.micro.yml -f compose.image.yml \
  up -d --no-build --wait --wait-timeout 240
curl --fail --silent --show-error --max-time 10 http://127.0.0.1:8080/actuator/health |
  python3 -c 'import json,sys; sys.exit(json.load(sys.stdin).get("status") != "UP")'
ln -sfn "$target" /opt/vium/current.next
mv -Tf /opt/vium/current.next /opt/vium/current
ROLLBACK
```

DB 복구는 별도 판단이 필요하다. CD 전환 후에는 기존 deployment.md의
Git checkout 방식 대신 이 릴리스 경로를 사용한다. 서버 애플리케이션 로그는
위 상태 확인 명령의 `ps`를 `logs --tail 100 app`으로 바꿔 직접 조회한다.

이미지와 릴리스는 자동 삭제하지 않는다. 현재 및 최근 정상 릴리스 최소 3개를
보존하고 디스크/ECR 용량을 확인한다. 단순히 ECR 최신 이미지 3개만 남기면
실패한 이미지 때문에 정상 롤백 이미지가 삭제될 수 있다.

배포 시작 시 `/opt/vium`과 Docker data root 파일시스템 각각의 여유 공간을
확인한다. 하나라도 2 GiB 미만이면 pull/컨테이너 교체 전에 중단한다. 2 GiB는
현재 소형 이미지 기준의 최소 안전 여유이며 다운로드·압축 해제 공간을 보장하는
계산값은 아니다. 이미지 크기가 커지면 기준을 높인다.

### 디스크 확인 및 수동 정리

SSM에서 아래를 실행해 현재 릴리스, 최근 성공 기록, 이미지 용량을 확인한다.
`succeeded` 파일은 bootstrap 또는 새 배포의 health 통과 후 기록된다.

```bash
sudo readlink -f /opt/vium/current
sudo find /opt/vium/releases -mindepth 2 -maxdepth 2 -name succeeded -printf '%T@ %h\n' | sort -nr
sudo docker system df
sudo docker image ls --digests --no-trunc
df -h /opt/vium
```

현재 릴리스와 성공 기록 상위 3개(중복 이미지면 더 많은 성공 릴리스를 보존해
서로 다른 정상 이미지 3개 확보)는 정리 대상에서 제외한다. 각 보존 디렉터리의
`compose.image.yml`에서 이미지 참조를 확인한다. 실패·미사용 릴리스도 필요한
조사를 마친 후에만 지운다. 아래 placeholder는 보존 목록에 없는 **정확한
단일 경로와 이미지 참조**로 바꾼다. 와일드카드나 `docker image prune -a`는
사용하지 않는다. 기존 초기 수동 릴리스는 첫 CD 검증이 끝날 때까지 보존한다.

```bash
sudo bash <<'CLEANUP'
set -euo pipefail
exec 9>/opt/vium/deploy.lock
flock -n 9
obsolete=/opt/vium/releases/REPLACE_WITH_VERIFIED_UNUSED_DIRECTORY
case "$obsolete" in /opt/vium/releases/*) ;; *) exit 1 ;; esac
test -d "$obsolete"
test "$(readlink -f "$obsolete")" != "$(readlink -f /opt/vium/current)"
# 위에서 최근 정상 릴리스 3개와 그 이미지가 아님을 확인한 대상만 지정한다.
docker image rm 'REPLACE_WITH_VERIFIED_UNUSED_IMAGE_REFERENCE'
rm -r -- "$obsolete"
CLEANUP
```

`docker image rm`에는 `--force`를 붙이지 않는다. 실행/정지 컨테이너가 사용하는
이미지라 삭제가 거부되면 원인을 확인한다. ECR 원격 이미지 정리는 별개이며,
동일한 정상 릴리스 보존 목록을 기준으로 수행해야 한다.

## 최초 검증

첫 실행 전에 EC2의 SSM 터미널에서 파일 **존재만** 검사한다. 비밀값을 출력하지 않는다.

```bash
sudo bash -c '
set -eu
cd /home/ubuntu/vium-releases/3fb5582
for file in .env.prod deploy/certs/global-bundle.pem compose.prod.yml compose.micro.yml; do
  test -s "$file" || { printf "Missing or empty: %s\n" "$file"; exit 1; }
done
printf "Bootstrap files present\n"
'
```

1. PR의 CI와 배포 스크립트 검사를 통과시킨다.
2. develop에서 검토 후 main으로 병합하면 CD가 시작된다.
3. Actions의 CD와 SSM command 결과가 Success인지 확인한다.
4. 외부 HTTPS health 및 실제 앱 API 요청을 확인한다.
5. 같은 main에서 수동 재실행해 불변 태그 재사용을 확인한다.

로컬 문법/모의 테스트는 실제 IAM, ECR, SSM, RDS 연결을 검증하지 않는다.
현재 애플리케이션 테스트는 H2 기반이며 PostgreSQL Flyway 사전 검증 job은
별도 후속 작업이다.
