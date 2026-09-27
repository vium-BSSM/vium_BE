# EC2 운영 배포

로컬에서 개발하고, 검증된 main 커밋을 운영 EC2에 배포한다. 이번 구성은 EC2 호스트의 Nginx → 앱 컨테이너 → RDS PostgreSQL을 전제로 한다. S3, CI/CD, AWS 리소스 생성은 별도 단계다.

## 사전 준비

- EC2: Docker Engine과 Compose v2 설치. 빌드까지 수행하므로 메모리 여유가 필요하다. 앱 컨테이너 제한은 1 GiB이며 부하를 보며 조정한다.
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

`.env.prod`의 모든 예시 값을 실제 값으로 교체한다. 위에서 생성한 키를 `JWT_SECRET`에 넣고 재배포 때 유지한다. `IMAGE_TAG`는 배포할 커밋 SHA를 사용한다. 비밀번호에 `$` 등이 있으면 작은따옴표로 감싸 Compose의 변수 치환을 막는다. `.env.prod`는 커밋하지 않으며 Docker 빌드 컨텍스트에도 포함되지 않는다.

RDS URL의 `sslmode=verify-full`은 인증서와 호스트를 검증한다. 인증서 파일은 컨테이너 사용자도 읽을 수 있어야 한다. 인증서 번들 갱신도 운영 시 관리한다.

`CORS_ALLOWED_ORIGINS`에는 실제 프론트 주소를 쉼표로 구분해 입력한다. 예: `https://app.example.com,https://www.example.com`. 경로·끝 슬래시·와일드카드는 넣지 않는다. 비워 두면 교차 출처 요청을 허용하지 않는다. 현재 인증은 Bearer 헤더 방식이므로 쿠키 credentials는 허용하지 않는다. 로컬 프론트 연동에는 로컬 환경변수로 별도 주소를 설정한다.

## 빌드 및 실행

```sh
docker compose --env-file .env.prod -f compose.prod.yml config --quiet
docker compose --env-file .env.prod -f compose.prod.yml build app
docker compose --env-file .env.prod -f compose.prod.yml up -d --no-build --wait --wait-timeout 180
docker compose --env-file .env.prod -f compose.prod.yml ps
curl --fail http://127.0.0.1:8080/actuator/health
```

Docker 빌드에서 기존 테스트와 bootJar를 실행한다. 테스트는 H2 기반이므로 운영 전 별도의 빈 PostgreSQL에서 Flyway V1~V4 및 앱 시작을 검증해야 한다. 앱 시작 시 Flyway가 적용되며 기존 DB의 V4 이메일 정규화 충돌은 배포를 중단시킬 수 있다. 적용된 마이그레이션은 수정하지 않는다.

`/actuator/health`는 DB를 포함한 상태를 확인한다. `/api/health`는 앱 응답만 확인하므로 DB 검증을 대신하지 않는다. Compose의 unhealthy 판정 자체는 자동 재시작을 하지 않는다. `restart: unless-stopped`는 프로세스 종료나 Docker 재기동 시 복구를 담당하며 별도 상태 모니터링이 필요하다.

## Nginx / HTTPS 연결

앱은 EC2의 `127.0.0.1:8080`에만 노출된다. Nginx는 컨테이너가 아닌 EC2 호스트에서 실행한다. 인증서를 설정한 HTTPS server 블록에 다음 location을 넣는다.

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Port $server_port;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header Forwarded "";
}
```

`nginx -t`로 검사 후 reload한다. 80은 HTTPS로 리다이렉트한다. 프록시가 전달 헤더를 덮어쓰므로 외부 요청의 임의 전달 헤더를 신뢰하지 않는다. HTTPS 설정 전에는 실제 계정으로 로그인하지 않는다.

## 배포 확인 및 복구

1. HTTPS에서 health 200 확인.
2. 운영 확인용 계정으로 회원가입 → 로그인 → 인증이 필요한 재고 조회 → 토큰 갱신 → 최신 Refresh Token으로 로그아웃 확인.
3. 토큰 없는 보호 API는 401, 허용한 프론트 Origin의 preflight는 성공하는지 확인.
4. 컨테이너 재시작 후에도 기존 계정과 데이터가 유지되는지 확인.

```sh
docker compose --env-file .env.prod -f compose.prod.yml logs --tail 100 app
```

로그에는 비밀번호·토큰·환경변수 전체를 출력하지 않는다. 로그 파일은 10 MB × 3개로 회전한다. JVM의 업무 날짜 기준은 Asia/Seoul로 고정하며 인증 세션은 기존 코드대로 UTC를 사용한다. 전체 DB 시간을 일괄 변환하지 않는다.

단일 앱 재생성 시 짧은 중단이 있다. 배포 전 이전 이미지 태그를 기록하고 이미지를 보관한다. 실패하면 `.env.prod`의 `IMAGE_TAG`를 이전 값으로 바꾼 뒤 위 `up --no-build` 명령으로 되돌린다. 이때 이미지를 다시 빌드하지 않는다. DB 마이그레이션은 이미지 롤백으로 되돌아가지 않으므로 이전 앱과의 호환성 및 RDS 백업 복원 절차를 별도로 확인한다.

참고: [Spring Security CORS](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html), [Compose 환경변수](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/).
