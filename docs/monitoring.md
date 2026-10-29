# Grafana Cloud 운영 모니터링

앱 → OTLP/HTTP(1분 주기, gzip) → Grafana Cloud로 메트릭을 직접 전송한다.
EC2에는 Prometheus·Grafana·Alloy 프로세스나 공개 포트를 추가하지 않는다.
JVM·HTTP 요청·HikariCP·Tomcat·Logback·프로세스의 자동 계측 메트릭을 사용한다.
로그 수집(Loki), tracing, 비즈니스 커스텀 메트릭은 포함하지 않는다.

## 설정과 의존성

Boot BOM이 관리하는 `spring-boot-opentelemetry`와 `micrometer-registry-otlp`를 사용한다.
Boot 4.1.1의 OTLP 자동 설정이 참조하는 OpenTelemetry 설정 클래스가 필요하므로
전자를 추가했다. starter 없이 자동 설정이 동작하며 tracing bridge와
`opentelemetry-exporter-otlp`는 포함하지 않는다. SDK의 trace 자료형 의존성이
있다는 사실만으로 trace가 수집·전송되지는 않는다.

공통 태그는 `application=vium-be`, `env=${DEPLOY_ENV:local}`이다. Compose는
`DEPLOY_ENV=prod`를 전달한다. 리소스 속성 `service.name`은 앱 이름에서,
`deployment.environment.name`은 DEPLOY_ENV에서 설정된다.
시간 단위는 seconds이며 HTTP SLO는 50ms, 100ms, 200ms, 500ms, 1s, 2s, 5s다.
자동 percentile histogram 대신 지정 버킷 7개만 사용해 시리즈 증가를 제한한다.
[Micrometer OTLP 설명](https://docs.micrometer.io/micrometer/reference/implementations/otlp.html)을 참고한다.

기본·dev·운영 모두 `OTLP_METRICS_ENABLED`가 없거나 false이면 전송하지 않는다.
테스트 YAML도 명시적으로 비활성화한다. 활성화 테스트는 sender를 대체하므로
실제 네트워크 연결 없이 종료 시 전송 실패 처리를 검증한다.
운영 actuator는 health만 노출하며 metrics·prometheus를 추가하지 않는다.

## Grafana Cloud 준비

1. 무료 스택을 만들고 제공되는 가까운 리전(ap-northeast 계열)을 선택한다.
2. Connections → OpenTelemetry(OTLP) 또는 Cloud Portal의 스택 OTLP 설정에서
   **OTLP endpoint와 Instance ID**를 확인한다.
3. 해당 스택의 `metrics:write`만 허용하는 access policy와 토큰을 만든다.
4. 신뢰할 수 있는 터미널에서 다음 형식으로 인증값을 만든다. 실제 토큰을
   셸 이력·채팅·로그에 남기지 않는다.

   ```sh
   printf '%s:%s' '<instance id>' '<access policy token>' | base64 | tr -d '\n'
   ```

결과 앞에 `Basic `을 붙여 `OTLP_METRICS_AUTHORIZATION`에 넣는다.
URL은 해당 스택의 endpoint 뒤에 `/v1/metrics`를 붙인 **완전한 메트릭 URL**이다.
이미 `/v1/metrics`가 있으면 중복해서 붙이지 않는다. `.env.prod.example`의 URL은
예시이므로 실제 스택에서 확인한다.
[Grafana OTLP 연결 안내](https://grafana.com/docs/grafana-cloud/send-data/otlp/send-data-otlp/)를 참고한다.

## 운영 반영과 확인

`/opt/vium/shared/.env.prod`(권한 600)에 다음 세 변수를 설정한다.

```sh
OTLP_METRICS_ENABLED=true
OTLP_METRICS_URL=https://<your-otlp-gateway>/otlp/v1/metrics
OTLP_METRICS_AUTHORIZATION='Basic <base64 credentials>'
```

[기존 CD 재배포 절차](cd.md)를 따른다. 환경변수는 컨테이너를 재생성해야 반영된다.
2~3분 뒤 Explore의 Prometheus 데이터소스에서 다음을 조회한다.

```promql
jvm_memory_used_bytes{application="vium-be",env="prod"}
```

실제 시리즈 라벨에서 `service_name="vium-be"` 또는 `job="vium-be"`가 있는지 확인한다.
리소스 속성의 라벨 변환 방식은 스택 설정에 따라 달라질 수 있다.
HTTP 요청도 발생시킨 후 `http_server_requests_seconds_count`와 `_bucket`을 확인한다.
401/403 전송 실패는 Instance ID·토큰 범위, 404는 endpoint 경로를 점검한다.
로컬 검증은 개발 DB와 필수 앱 환경변수를 준비하고 같은 OTLP 자격증명을 안전하게
환경에 주입한 뒤 `OTLP_METRICS_ENABLED=true ./gradlew bootRun`으로 실행한다.
로컬의 `env=local`을 운영 알림에 섞지 않는다.

끄려면 `OTLP_METRICS_ENABLED=false`로 바꾸고 재배포한다.
`/actuator/health` 정상 응답과 인증된 요청에서 `/actuator/metrics`,
`/actuator/prometheus`의 비노출을 확인한다. 현재 공통 예외 처리기는
미존재 경로의 `NoResourceFoundException`도 500으로 변환하므로 인증된 요청의
실제 응답은 404 대신 500이다. 비노출 테스트는 해당 예외를 확인한다.
404 반환 복구는 공통 오류 처리의 별도 수정 사항이다. 인증 없는 요청은 보안 필터가
먼저 401을 반환할 수 있으므로 상태 코드만으로 비노출을 판단하지 않는다.

배포 전후 `docker stats --no-stream vium-prod-app-1`로 메모리를 비교하고 배포 기록에
남긴다. 레지스트리·HTTP 클라이언트의 메모리와 스레드 증가량은 실제 측정한다.
`compose.micro.yml`의 RAM 512 MiB / RAM+swap 768 MiB 제한은 유지한다.
실제 Cloud 수신·메모리 비교는 운영 자격증명과 배포 환경에서 별도로 검증해야 한다.

## 대시보드

Dashboards → Import에서 아래 ID를 가져온다.

- [4701 — JVM (Micrometer)](https://grafana.com/grafana/dashboards/4701-jvm-micrometer/)
- [19004 — Spring Boot 3.x Statistics](https://grafana.com/grafana/dashboards/19004-spring-boot-statistics/)

데이터소스는 `grafanacloud-<stack>-prom`, application 변수는 `vium-be`로 설정한다.
공개 대시보드는 Prometheus 메트릭을 기준으로 하므로 OTLP 변환 후 그대로 모든
패널이 동작한다고 보장하지 않는다. 빈 패널은 Explore에서 `_seconds`, `_total`,
instance/job 라벨과 쿼리를 비교한다. 별도 JVM extras가 필요한 패널은 기본 수집에
포함되지 않는다. 운영 대시보드 쿼리에는 `env="prod"` 필터를 적용한다.

## 알림

Alerting → Contact points에서 이메일 또는 Discord 웹훅을 설정하고 테스트한다.
Alert rules에서 Prometheus 데이터소스와 다음 조건, pending 기간을 설정하고
notification policy를 연결한다. 평가 간격은 1분으로 시작한다.

| 이름 | PromQL 조건 | 지속 |
|---|---|---|
| 앱 메트릭 끊김 | `absent_over_time(jvm_memory_used_bytes{application="vium-be",env="prod"}[5m]) > 0` | 5m |
| 5xx 비율 | `sum(rate(http_server_requests_seconds_count{application="vium-be",env="prod",status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{application="vium-be",env="prod"}[5m])) > 0.05` | 5m |
| p95 지연 | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="vium-be",env="prod"}[5m]))) > 2` | 10m |
| 힙 사용률 | `sum(jvm_memory_used_bytes{application="vium-be",env="prod",area="heap"}) / sum(jvm_memory_max_bytes{application="vium-be",env="prod",area="heap"}) > 0.9` | 10m |
| DB 커넥션 대기 | `max(hikaricp_connections_pending{application="vium-be",env="prod"}) > 0` | 5m |

실제 메트릭 이름·라벨은 Explore에서 확인하고 맞춘다. p95는 지정 버킷 기반
근사치다. 5xx가 아직 없거나 트래픽이 없으면 비율 식은 결과가 없거나 NaN일 수 있다.
이때 No Data를 정상으로 처리하고 수집 중단은 별도 끊김 규칙으로 확인한다.
끊김 규칙도 정상 수집 시 결과가 없으므로 No Data는 Normal로 둔다.
5분 조회 창과 5분 pending 때문에 끊김 감지는 마지막 수집 후 약 10분이 걸린다.

## 사용량과 보안

무료 플랜은 활성 메트릭 시리즈 1만, 보존 14일을 기준으로 계획했다.
변경 가능한 한도는 [Grafana 가격 안내](https://grafana.com/pricing/)와 스택 Usage에서 확인한다.
JVM·시스템 약 200개에 HTTP URI·상태·메서드 등 라벨 조합과 버킷 수가 곱해진다.
URI 약 35개 기준 1~2천 시리즈는 초기 추정이며 실제 조합에 따라 더 늘 수 있다.
새 메트릭·histogram 추가 전 시리즈 수를 계산하고 배포 후 Usage로 확인한다.

매칭되지 않는 경로는 프레임워크의 낮은 카디널리티 URI 라벨(예: NOT_FOUND/UNKNOWN)로
묶인다. 사용자 ID·원문 URL·쿼리 문자열을 태그로 넣지 않는다.
토큰은 운영 `.env.prod`에만 보관하고 Git·로그에 기록하지 않는다.
유출되면 Grafana Cloud에서 즉시 폐기하고 재발급한 뒤 재배포한다.
대시보드 import와 알림 등록은 운영자가 위 절차에 따라 수행한다.
