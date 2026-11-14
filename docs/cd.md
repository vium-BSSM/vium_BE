# ECR / SSM 운영 배포

`.github/workflows/cd.yml`은 main push 또는 main에서의 수동 실행을 처리한다.
Dockerfile의 `test bootJar`가 성공해야 AWS 인증과 배포를 진행한다. 별도 CI
실행 완료를 기다리는 구조가 아니라 CD 자체에서 같은 검증을 수행한다.
PR/develop에는 배포하지 않는다. 대기 중 새 main이 생긴 오래된 실행은 건너뛴다.
동시 배포는 GitHub concurrency와 서버 flock으로 제한하며 실행 중인 배포는
자동 취소하지 않는다. GitHub 대기 실행은 최신 실행으로 교체될 수 있다.

## 준비된 AWS 설정

- 서울 ECR `vium-be`, Immutable, AES-256.
- 수명주기 정책 정의: [ecr-lifecycle-policy.json](../scripts/deploy/ecr-lifecycle-policy.json).
  미태그 이미지는 push 후 7일, 전체 이미지는 최근 push 10개를 보존한다.
  적용은 아래 수동 절차를 따르며 CD·EC2 역할 권한은 바꾸지 않는다.
  롤백은 로컬 이미지를 사용하므로 ECR 만료와 무관하다.
- GitHub OIDC 역할 `vium-github-actions`: ECR 로그인/푸시/DescribeImages,
  `AWS-RunShellScript` 및 운영 EC2 하나에 SendCommand, GetCommandInvocation.
- 신뢰 정책: audience `sts.amazonaws.com`, main의 OIDC subject만 허용.
  GitHub Environment는 현재 사용하지 않는다. 추후 환경을 사용하면 subject와
  환경의 허용 브랜치/승인 정책을 함께 변경해야 한다.
- EC2 `vium-ec2-role`: AmazonSSMManagedInstanceCore 및 해당 저장소 pull 권한,
  이미지 저장소용 S3 권한([배포 안내](deployment.md) 참고).
  SSM Agent online, AWS CLI, Docker Compose, Python 3, curl, flock 필요.
- GitHub repository Variables: `AWS_ROLE_ARN`, `AWS_REGION`,
  `ECR_REPOSITORY`, `EC2_INSTANCE_ID`. 장기 액세스 키는 사용하지 않는다.

OIDC 인증이 실패하면 실제 저장소의 subject 형식도 확인한다. GitHub의 신규
저장소/옵트인 설정에서는 조직·저장소 ID가 subject에 포함될 수 있다. 이때도
와일드카드로 넓히지 않고 실제 main subject와 신뢰 정책을 일치시킨다.

`cd.yml`의 액션(`checkout`, `configure-aws-credentials`) 업데이트는 Dependabot
PR의 CI에서 실행되지 않고 main 배포 때 처음 실행된다. 릴리스 후 CD의
`Authenticate to AWS with OIDC` step과 Summary의 Annotations를 확인해야
검증이 끝난다. #104에서는 `configure-aws-credentials` v6.3.0
(`e1253824e5c10ff9df46874f81ed3ec929e19cfd`)이 실행되고 Node.js 20 deprecation
경고가 없는지, `Publish image and deploy through SSM` 성공과 운영 health `UP`을
확인한다. main push 실행으로 확인하거나 Actions → CD → Run workflow에서
`main`을 선택한다. 액션 업데이트로 실패하면 실패 로그를 이슈에 남기고
`cd.yml`의 해당 액션을 이전 SHA로 되돌리는 fix PR을 develop → main으로 반영한다.
OIDC 인증 실패는 배포 step 전에 중단되므로 기존 운영 앱은 유지된다.

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
중단한다. GitHub에는 command ID와 검증된 `CLEANUP` 요약만 기록한다. 서버 원문 로그는 복사하지 않는다.

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
자동 정리는 롤백 대상과 최근 성공한 서로 다른 이미지 3개를 남긴다. 그보다 오래된
릴리스로 돌아가려면 ECR 로그인 후 `docker pull <compose.image.yml의 digest 참조>`가
먼저 필요하다. 해당 이미지가 ECR에도 없으면 그 릴리스로는 돌아갈 수 없다.

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

### 자동 정리

CD 성공 후 `current` 교체가 끝나면 같은 `deploy.lock`을 잡은 상태에서
[scripts/deploy/cleanup.py](../scripts/deploy/cleanup.py)가 정리한다. 실패한 배포에는
실행하지 않는다. 정리 실패·300초 시간 초과는 경고만 남기며 배포 성공을 바꾸지 않는다.

| 릴리스 분류 | 처리 |
|---|---|
| current, 배포 직전 previous | 항상 보존(오래된 수동 롤백 대상도 포함) |
| `bootstrap.<6자>` | 항상 보존. ECR에 없는 수동 빌드 이미지도 보호 |
| 최근 성공 SHA 릴리스 | `succeeded` mtime 내림차순으로 서로 다른 이미지 3개가 될 때까지 보존 |
| 나머지 SHA 릴리스 | 오래된 성공 및 실패·중단 릴리스 삭제 |
| 알 수 없는 이름, 심볼릭 링크, 일반 파일 | 건드리지 않고 개수 보고 |

같은 이미지 재배포가 끼어 있으면 최근 성공 디렉터리는 3개보다 많을 수 있다.
디렉터리를 먼저 지운 뒤 이미지를 정리한다. 디렉터리 삭제에 실패하면 해당 이미지도
보호한다. 보존 릴리스 및 실행·정지 컨테이너의 이미지 ID는 모두 남긴다.
배포 ECR 저장소 참조만 가진 비보존 이미지를 `docker image rm <ID>`로 삭제한다.
다른 저장소 태그가 함께 있으면 건너뛴다. 모든 prune 명령과 `--force`는 사용하지 않는다.
다른 저장소 이미지 및 `/home/ubuntu/vium-releases/` 아카이브는 자동 삭제하지 않는다.

보존 이미지 해석 또는 Docker 조회가 실패하면 이미지 삭제 전체를 건너뛰는
fail-closed 방식이다. 이때 디렉터리 정리는 계속한다. current/previous가 releases
밖에 있거나 최근 성공 이미지 범위를 판단할 수 없으면 아무것도 삭제하지 않는다.
비밀 파일 내용이나 Docker 원문 오류는 출력하지 않는다.

Actions의 `Publish image and deploy through SSM` step에서 마지막 `CLEANUP` 줄을 확인한다.
`status=ok`는 정상 완료, `partial`은 일부 삭제 실패 또는 이미지 단계 중단,
`error`는 정리 판단·측정 등의 오류다. `dry_run=1`이면 삭제 수는 **삭제 예정 수**다.
`releases_kept/removed/unknown`은 보존/삭제/미분류 디렉터리 수,
`images_kept/removed/failed/skipped`는 보호 이미지 ID/삭제/실패/다른 저장소 중복 참조 수다.
이미지 단계 전체 중단은 `images_skipped=all`이다. `free_kib_before/after`는
`/opt/vium`과 Docker root 중 더 작은 가용 KiB이며, `free_kib_min=4194304`는 경고 기준이다.
요약이 잘려 없으면 `Cleanup summary not found; inspect SSM command ...` 안내를 따라
SSM 출력을 확인한다. 이미지 레이어 공유 때문에 이미지 표시 크기 합계와 회수량은 다르다.

정리 후 두 경로 중 하나라도 4 GiB 미만이면 경고한다. Grafana의 디스크 4 GiB/10분
알림도 함께 사용한다([모니터링](monitoring.md)). 첫 배포는 다음 표지 파일로 미리 본다.

```bash
sudo touch /opt/vium/shared/cleanup-dry-run
```

배포 후 `dry_run=1`과 SSM 보존·삭제 목록을 확인한다. current/previous/bootstrap와
최근 서로 다른 정상 이미지 3개가 보호되는지 확인한 뒤 다음 명령으로 실제 정리를 켠다.

```bash
sudo rm /opt/vium/shared/cleanup-dry-run
```

Actions → CD → Run workflow(`main`)로 다시 배포한다. `dry_run=0`, 삭제 수,
여유 공간 증가, `docker system df`, health `UP`, 직전 성공 릴리스 보존을 확인한다.
다음 배포에서도 보존 범위가 유지되는지 확인한다. 운영 dry-run·실제 정리 검증은 미완료다.

배포 시작 시 `/opt/vium`과 Docker data root 파일시스템 각각의 여유 공간을
확인한다. 하나라도 2 GiB 미만이면 pull/컨테이너 교체 전에 중단한다. 2 GiB는
현재 소형 이미지 기준의 최소 안전 여유이며 다운로드·압축 해제 공간을 보장하는
계산값은 아니다. 이미지 크기가 커지면 기준을 높인다.

### 디스크 확인 및 수동 정리 (대체 절차)

자동 정리가 `partial`/`error`일 때, 시작 시 2 GiB 미만으로 배포가 막혔을 때,
다른 저장소 이미지나 아카이브를 정리할 때 사용한다.

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
이미지라 삭제가 거부되면 원인을 확인한다. ECR 원격 정리는 아래 수명주기 정책 절차를 따른다.

## ECR 수명주기 정책 적용 (운영 반영 미완료)

관리자에게 해당 저장소의 `ecr:GetLifecyclePolicy`, `ecr:PutLifecyclePolicy`,
`ecr:StartLifecyclePolicyPreview`, `ecr:GetLifecyclePolicyPreview` 권한이 필요하다.
이미지 수 확인에는 `ecr:DescribeImages`를 사용한다. `vium-github-actions`와
`vium-ec2-role`은 변경하지 않으며 만료는 ECR 서비스가 수행한다.

```bash
aws ecr describe-images --repository-name vium-be --region ap-northeast-2 \
  --query 'length(imageDetails)'
aws ecr start-lifecycle-policy-preview --repository-name vium-be --region ap-northeast-2 \
  --lifecycle-policy-text file://scripts/deploy/ecr-lifecycle-policy.json
aws ecr get-lifecycle-policy-preview --repository-name vium-be --region ap-northeast-2
```

적용 전 이미지 수와 저장소 크기를 기록한다. 미리보기가 완료되면 만료 대상에
current와 최근 서로 다른 정상 이미지 3개의 digest가 **없는지 확인한 뒤** 적용한다.
실패한 push도 최근 10개에 포함되므로 정상 이미지 3개 보존을 원격 정책만으로 보장하지 않는다.

```bash
aws ecr put-lifecycle-policy --repository-name vium-be --region ap-northeast-2 \
  --lifecycle-policy-text file://scripts/deploy/ecr-lifecycle-policy.json
aws ecr get-lifecycle-policy --repository-name vium-be --region ap-northeast-2
```

콘솔에서는 ECR → `vium-be` → Lifecycle policy에서 같은 JSON을 사용한다.
만료는 비동기이므로 24시간 안에 이미지 수가 10개 이하로 줄었는지 확인한다.
2026-11-11 사전 확인 시 정책은 없었고 원격 이미지 수는 미기록이다.
로컬 ECR 이미지 10개, bootstrap 1개, hello-world 1개, 릴리스 14개,
아카이브 147M였다. hello-world 삭제와 아카이브 정리는 운영자가 별도로 판단한다.

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
애플리케이션 테스트는 H2 기반이다. PostgreSQL Flyway·`ddl-auto: validate` 사전 검증은
PR CI의 `PostgreSQL migration check`가 빈 DB 기준으로 수행한다([CI 안내](ci.md)).
CD 자체는 이 검사를 다시 실행하지 않는다.
