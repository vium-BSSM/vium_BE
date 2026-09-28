# EC2 운영 배포

로컬에서 개발하고, 검증된 main 커밋을 운영 EC2에 배포한다. 이번 구성은 EC2 호스트의 Nginx → 앱 컨테이너 → RDS PostgreSQL을 전제로 한다. CI는 GitHub Actions로 실행한다([CI 안내](ci.md)). CD, S3, AWS 리소스 생성은 별도 단계다.

## 사전 준비

- EC2: Docker Engine과 Compose v2 설치. 이미지는 로컬 또는 별도 빌드 머신에서 만들고 EC2에서는 실행만 한다. 이 문서의 실행 명령은 t3.micro 기준으로 RAM 512 MiB, RAM과 스왑 합계 768 MiB 제한을 적용하며 부하를 보며 조정한다.
- RDS: PostgreSQL 16, 데이터베이스 `vium` 생성, public access 비활성화. 같은 VPC의 EC2 보안 그룹에서만 5432 접근 허용. 자동 백업 활성화.
- EC2: 공개 포트는 HTTPS 443, 인증서 발급·리다이렉트용 80. SSH가 필요하면 관리자 IP만 22 허용. 8080과 5432는 공개하지 않는다.
- 도메인: 운영 API 주소를 EC2에 연결하고 호스트 Nginx에 유효한 TLS 인증서를 설치한다.
- 운영 DB에는 개발용 데이터를 복사하지 않는다. `prod` 프로필만 사용한다.

## 환경변수와 RDS 인증서

저장소 루트에서 실행한다.

```sh
cp .env.prod.example .env.prod
chmod 600 .env.prod
mkdir -p deploy/certs
curl --fail --show-error --location https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem -o deploy/certs/global-bundle.pem
openssl rand -base64 32
```

`.env.prod`의 모든 예시 값을 실제 값으로 교체한다. 위에서 생성한 키를 `JWT_SECRET`에 넣고 재배포 때 유지한다. `IMAGE_TAG`는 이 파일에 저장하지 않고 아래 절차에서 셸 환경변수로 지정한다. 기존 파일에 있다면 삭제한다. 비밀번호에 `$` 등이 있으면 작은따옴표로 감싸 Compose의 변수 치환을 막는다. `.env.prod`는 커밋하지 않으며 Docker 빌드 컨텍스트에도 포함되지 않는다.

RDS URL의 `sslmode=verify-full`은 인증서와 호스트를 검증한다. 인증서 파일은 컨테이너 사용자도 읽을 수 있어야 한다. 인증서 번들 갱신도 운영 시 관리한다.

`CORS_ALLOWED_ORIGINS`에는 실제 프론트 주소를 쉼표로 구분해 입력한다. 예: `https://app.example.com,https://www.example.com`. 경로·끝 슬래시·와일드카드는 넣지 않는다. 비워 두면 교차 출처 요청을 허용하지 않는다. 현재 인증은 Bearer 헤더 방식이므로 쿠키 credentials는 허용하지 않는다. 로컬 프론트 연동에는 로컬 환경변수로 별도 주소를 설정한다. 빈 값에서도 앱 기동은 정상이며, 다른 도메인의 웹 프론트를 연결할 때 반드시 허용 주소를 설정하고 preflight를 확인한다.

## 릴리스 이미지 빌드 — 로컬 또는 별도 빌드 머신

운영 EC2에서는 빌드하지 않는다. Gradle과 테스트 JVM이 운영 앱과 메모리를 경쟁하는 것을 피한다. Dockerfile의 BuildKit 캐시 마운트는 Gradle 배포판과 의존성을 다음 빌드에서도 재사용한다. 캐시가 삭제되거나 다른 빌드 머신을 쓰면 다시 다운로드한다.

먼저 아래 변경을 커밋·병합한 뒤 배포할 main 커밋을 선택한다. 최초에는 저장소를 clone한다. 이후 명령은 Bash에서 실행한다. macOS의 기본 zsh를 사용 중이면 먼저 터미널에서 `bash`를 실행한 뒤 아래 블록을 붙여넣는다. zsh의 대화형 주석 설정에 따라 `#` 주석 줄이 명령으로 처리될 수 있으므로 zsh에 직접 붙여넣지 않는다. 기존 clone은 저장소 루트에서 시작한다. 각 Bash 블록은 괄호까지 함께 실행한다. `set -euo pipefail`은 서브셸 안에만 적용되어 실패하면 해당 블록이 중단되고 일반적인 대화형 SSH 셸은 유지된다. 부모 셸에서 이미 `set -e`를 켰다면 먼저 새 세션을 열어 실행한다. 작업 트리가 변경된 상태면 중단하며, 실제 checkout된 전체 SHA를 태그로 사용한다.

```bash
(
set -euo pipefail
trap 'printf "배포 절차가 %s행에서 중단됐습니다. 위 오류를 확인하세요.\n" "$LINENO" >&2' ERR
git clone https://github.com/vium-BSSM/vium_BE.git
cd vium_BE
# 기존 clone을 쓰는 경우 위 두 줄은 생략
RELEASE_SHA='<배포할-main-커밋-SHA>'
test -z "$(git status --porcelain)"
git fetch origin
git checkout --detach "$RELEASE_SHA"
git merge-base --is-ancestor HEAD origin/main
test -z "$(git status --porcelain)"
export IMAGE_TAG="$(git rev-parse HEAD)"
# t3 계열은 linux/amd64, t4g 계열은 linux/arm64
TARGET_PLATFORM=linux/amd64
# 같은 태그가 이미 있으면 재빌드하지 않고 기존 산출물을 재사용
if ! docker image inspect "vium-be:$IMAGE_TAG" >/dev/null 2>&1; then
    docker buildx build --platform "$TARGET_PLATFORM" --load -t "vium-be:$IMAGE_TAG" .
fi
docker image inspect "vium-be:$IMAGE_TAG" --format '{{.Os}}/{{.Architecture}} {{.Id}}'
RELEASE_DIR="$HOME/vium-releases"
mkdir -p "$RELEASE_DIR"
docker save -o "$RELEASE_DIR/vium-be-$IMAGE_TAG.tar" "vium-be:$IMAGE_TAG"
ssh '<SSH사용자>@<EC2주소>' 'mkdir -p "$HOME/vium-releases"'
scp "$RELEASE_DIR/vium-be-$IMAGE_TAG.tar" '<SSH사용자>@<EC2주소>:vium-releases/'
)
```

출력한 아키텍처가 대상 EC2와 일치하는지 확인한다. 같은 SHA 이미지를 덮어쓰지 말고 배포한 이미지의 ID와 아카이브를 보관한다. 기본 이미지·외부 의존성까지 고정한 재현 빌드는 아니므로 같은 SHA의 재빌드가 같은 이미지임을 보장하지 않는다. 향후 CI/CD에서는 레지스트리의 불변 태그와 digest로 관리한다.

아카이브는 빌드 머신과 EC2의 `~/vium-releases/`에 보관한다. EC2에서 이 경로가 EBS 등 디스크 파일시스템에 있는지 `findmnt -T "$HOME/vium-releases"`로 확인한다. AL2023의 기본 `/tmp`는 tmpfs이므로 릴리스 보관에 사용하지 않는다. 현재 운영 릴리스와 직전 정상 릴리스를 반드시 포함해 최근 정상 릴리스 최소 3개를 보관하고, 새 배포 검증 후 불필요한 파일만 수동 정리한다. 파일 크기·압축률은 이미지 저장 형식에 따라 달라지므로 고정 용량으로 가정하지 않는다. 디스크 보관은 재부팅에는 유지되지만 EC2/EBS 삭제에 대비한 백업을 대신하지 않는다.

`docker image prune -a`는 태그가 있어도 컨테이너에서 참조하지 않는 롤백 이미지를 삭제할 수 있다. 실행 전 보관 아카이브를 확인하며, 이번 절차에서는 자동 prune을 하지 않는다.

## t3.micro 메모리 설정

이 문서는 t3.micro(1 GiB RAM) 기준이며 모든 Compose 명령에 `-f compose.prod.yml -f compose.micro.yml`을
함께 사용한다. `compose.micro.yml`은 컨테이너 RAM을 512 MiB, RAM과 스왑의
합계를 768 MiB, JVM 최대 힙을 RAM 제한의 50%로 설정한다. 호스트 스왑은
별도로 설정해야 한다. 스왑은 RAM을 대체하지 않으며, 지속적인 메모리 압박이
있으면 인스턴스 사양을 높인다. 이 설정의 기동 검증은 부하 테스트를 대신하지 않는다.

더 큰 인스턴스에서 기본 1 GiB 컨테이너 제한을 사용하려면 배포·로그·롤백 명령 모두에서 micro 파일의 `-f` 옵션을 뺀다. 인스턴스를 키우는 것만으로 앱 제한이 자동 변경되지는 않는다.

CI 실행과 적용 범위는 [ci.md](ci.md)를 참고한다.

## EC2에서 이미지 로드 및 실행

최초에는 위 저장소를 EC2에도 clone한다. 비공개 저장소라면 읽기 전용 deploy key 등 읽기 권한을 준비한다. Deploy key를 사용하면 HTTPS 대신 `git@github.com:vium-BSSM/vium_BE.git` SSH URL로 clone한다. 저장소 루트에서 아래 명령으로 같은 커밋의 Compose를 준비한다. 환경변수·인증서는 앞 절차대로 설정한다. 재배포 때 `.env.prod`를 예시 파일로 덮어쓰지 않는다.

```bash
(
set -euo pipefail
trap 'printf "배포 절차가 %s행에서 중단됐습니다. 위 오류를 확인하세요.\n" "$LINENO" >&2' ERR
RELEASE_SHA='<전송한-이미지의-전체-커밋-SHA>'
test -z "$(git status --porcelain)"
git fetch origin
git checkout --detach "$RELEASE_SHA"
git merge-base --is-ancestor HEAD origin/main
test -z "$(git status --porcelain)"
export IMAGE_TAG="$(git rev-parse HEAD)"
docker load -i "$HOME/vium-releases/vium-be-$IMAGE_TAG.tar"
docker image inspect "vium-be:$IMAGE_TAG" --format '{{.Id}}'
docker compose --env-file .env.prod -f compose.prod.yml -f compose.micro.yml config --quiet
# 기존 배포가 있다면 출력된 이전 이미지 태그를 기록한다
CURRENT_CONTAINER=$(docker compose --env-file .env.prod -f compose.prod.yml -f compose.micro.yml ps -q app)
if [ -n "$CURRENT_CONTAINER" ]; then
    docker inspect "$CURRENT_CONTAINER" --format '{{.Config.Image}}'
fi
docker compose --env-file .env.prod -f compose.prod.yml -f compose.micro.yml up -d --no-build --wait --wait-timeout 180
docker compose --env-file .env.prod -f compose.prod.yml -f compose.micro.yml ps
curl --fail http://127.0.0.1:8080/actuator/health
)
```

빌드 머신의 이미지 ID와 EC2에 로드된 이미지 ID가 같아야 한다. Compose에는 `build`가 없고 `pull_policy: never`이므로 로드한 이미지가 없으면 실패한다. 서브셸에서 export한 `IMAGE_TAG`는 블록 종료 후 부모 셸에 남지 않는다. 아래 로그 확인처럼 후속 Compose 명령마다 배포한 전체 SHA를 명시한다. 새 SSH 세션에서도 동일하다.

Docker 빌드에서 기존 테스트와 bootJar를 실행한다. 테스트는 H2 기반이므로 운영 전 별도의 빈 PostgreSQL에서 Flyway V1~V4 및 앱 시작을 검증해야 한다. 앱 시작 시 Flyway가 적용되며 기존 DB의 V4 이메일 정규화 충돌은 배포를 중단시킬 수 있다. 적용된 마이그레이션은 수정하지 않는다.

`/actuator/health`는 DB를 포함한 상태를 확인한다. `/api/health`는 앱 응답만 확인하므로 DB 검증을 대신하지 않는다. Compose의 unhealthy 판정 자체는 자동 재시작을 하지 않는다. `restart: unless-stopped`는 프로세스 종료나 Docker 재기동 시 복구를 담당하며 별도 상태 모니터링이 필요하다.

## Nginx / HTTPS 연결

앱은 EC2의 `127.0.0.1:8080`에만 노출된다. Nginx는 EC2 호스트에서 실행한다. 다음은 인증서를 발급받은 후 적용할 최소 server 설정이다. `api.example.com`과 인증서 경로를 실제 값으로 바꾼다. 인증서가 없는 상태에서 이 설정을 먼저 활성화하면 Nginx 검증이 실패한다. 최초 발급은 DNS 인증이나 80번 포트 임시 설정을 사용하고 자동 갱신을 설정한다.

```nginx
server {
    listen 80;
    server_name api.example.com;
    return 301 https://api.example.com$request_uri;
}

server {
    listen 443 ssl;
    server_name api.example.com;
    ssl_certificate /etc/letsencrypt/live/api.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Port $server_port;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Prefix "";
        proxy_set_header Forwarded "";
    }
}
```

`nginx -t`로 검사 후 reload한다. 80은 HTTPS로 리다이렉트한다. 프록시가 전달 헤더를 덮어쓰므로 외부 요청의 임의 전달 헤더를 신뢰하지 않는다. HTTPS 설정 전에는 실제 계정으로 로그인하지 않는다.

## 배포 확인 및 복구

1. HTTPS에서 health 200 확인.
2. 운영 확인용 계정으로 회원가입 → 로그인 → 인증이 필요한 재고 조회 → 토큰 갱신 → 최신 Refresh Token으로 로그아웃 확인.
3. 토큰 없는 보호 API는 401, 허용한 프론트 Origin의 preflight는 성공하는지 확인.
4. 컨테이너 재시작 후에도 기존 계정과 데이터가 유지되는지 확인.

```sh
IMAGE_TAG='<현재-배포한-전체-커밋-SHA>' docker compose --env-file .env.prod -f compose.prod.yml -f compose.micro.yml logs --tail 100 app
```

로그에는 비밀번호·토큰·환경변수 전체를 출력하지 않는다. 로그 파일은 10 MB × 3개로 회전한다. JVM의 업무 날짜 기준은 Asia/Seoul로 고정하며 인증 세션은 기존 코드대로 UTC를 사용한다. 전체 DB 시간을 일괄 변환하지 않는다.

단일 앱 재생성 시 짧은 중단이 있다. 배포 전 이전 이미지 태그를 기록하고 이미지를 보관한다. 실패하면 다음과 같이 이전 커밋의 Compose와 보관한 이미지를 함께 사용한다. 이전 릴리스가 이 배포 방식을 지원하는지 먼저 확인한다. 초기 릴리스에는 `compose.micro.yml`이 없으므로, checkout 전에 현재 검증된 micro 파일을 작업 트리 밖에 복사한다. 대상 커밋에 micro 파일이 있으면 해당 파일을 우선 사용하고, 없으면 복사본을 사용한다. 이 대체 절차는 대상 `compose.prod.yml`도 `app` 서비스와 동일한 환경변수·인증서 경로를 사용하는 경우에 적용한다.

초기 운영 커밋 `3fb558204c2d7ece23db64d8c6af1987666a6c1d`의 아카이브는 실제 EC2의 `~/vium-releases/3fb5582/vium-be.tar`에 있다. 아래 `IMAGE_ARCHIVE`에 이 경로를 지정한다. 이후 릴리스는 `~/vium-releases/vium-be-<전체-SHA>.tar` 경로를 사용한다. 이 블록은 `.env.prod`와 `deploy/certs`가 준비된 EC2 저장소 checkout에서 실행한다.

```bash
(
set -euo pipefail
trap 'printf "배포 절차가 %s행에서 중단됐습니다. 위 오류를 확인하세요.\n" "$LINENO" >&2' ERR
ROLLBACK_SHA='<이전에-배포한-전체-커밋-SHA>'
IMAGE_ARCHIVE='<해당-릴리스의-아카이브-절대경로>'
test -z "$(git status --porcelain)"
test -f compose.micro.yml
mkdir -p "$HOME/vium-releases"
MICRO_OVERRIDE=$(mktemp "$HOME/vium-releases/compose.micro.rollback.XXXXXX")
cp compose.micro.yml "$MICRO_OVERRIDE"
git checkout --detach "$ROLLBACK_SHA"
export IMAGE_TAG="$(git rev-parse HEAD)"
if [ -f compose.micro.yml ]; then
    MICRO_OVERRIDE="$PWD/compose.micro.yml"
fi
if ! docker image inspect "vium-be:$IMAGE_TAG" >/dev/null 2>&1; then
    docker load -i "$IMAGE_ARCHIVE"
fi
docker image inspect "vium-be:$IMAGE_TAG" >/dev/null
printf '롤백 IMAGE_TAG=%s\n롤백 MICRO_OVERRIDE=%s\n' "$IMAGE_TAG" "$MICRO_OVERRIDE"
docker compose --env-file .env.prod -f compose.prod.yml -f "$MICRO_OVERRIDE" config --quiet
docker compose --env-file .env.prod -f compose.prod.yml -f "$MICRO_OVERRIDE" up -d --no-build --wait --wait-timeout 180
curl --fail http://127.0.0.1:8080/actuator/health
)
```

롤백 블록이 출력한 `IMAGE_TAG`와 `MICRO_OVERRIDE` 경로를 기록한다. 두 변수는
서브셸 종료 후 유지되지 않는다. 초기 릴리스로 롤백한 뒤에는 위의 일반 로그
명령 대신 아래처럼 출력된 복사본의 절대경로를 지정한다. 같은 checkout의
저장소 루트에서 실행하며, `ps`, `logs`, 재시작 등 후속 Compose 명령 모두에
동일한 파일을 사용한다.

```bash
(
set -euo pipefail
export IMAGE_TAG='<롤백-블록에서-출력한-전체-SHA>'
MICRO_OVERRIDE='<롤백-블록에서-출력한-micro-파일-절대경로>'
test -f "$MICRO_OVERRIDE"
docker compose --env-file .env.prod -f compose.prod.yml -f "$MICRO_OVERRIDE" ps
docker compose --env-file .env.prod -f compose.prod.yml -f "$MICRO_OVERRIDE" logs --tail 100 app
)
```

`~/vium-releases/compose.micro.rollback.*` 복사본은 롤백할 때마다 남는다.
다음 정상 배포와 health 확인을 마친 뒤, 현재 실행 및 보관 중인 롤백 절차가
참조하지 않는 복사본만 수동 정리한다. 사용 중인 복사본은 삭제하지 않는다.

이때 이미지를 다시 빌드하지 않는다. DB 마이그레이션은 이미지 롤백으로 되돌아가지 않으므로 이전 앱과의 호환성 및 RDS 백업 복원 절차를 별도로 확인한다.

참고: [Spring Security CORS](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html), [Compose 환경변수](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/).

운영 참고: [AL2023 /tmp](https://docs.aws.amazon.com/linux/al2023/ug/filesystem-slash-tmp.html), [Docker 이미지 정리](https://docs.docker.com/reference/cli/docker/image/prune/).
