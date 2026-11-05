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
`/actuator/prometheus`의 비노출을 확인한다. 인증된 미존재 경로 요청은 공통 형식의
404를 반환한다. 인증 없는 요청은 보안 필터가
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
| 5xx 비율 | `sum(rate(http_server_requests_seconds_count{application="vium-be",env="prod",uri!~"/actuator.*",status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{application="vium-be",env="prod",uri!~"/actuator.*"}[5m])) > 0.05` | 5m |
| p95 지연 | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="vium-be",env="prod",uri!~"/actuator.*"}[5m]))) > 2` | 10m |
| 힙 사용률 | `sum(jvm_memory_used_bytes{application="vium-be",env="prod",area="heap"}) / sum(jvm_memory_max_bytes{application="vium-be",env="prod",area="heap"}) > 0.9` | 10m |
| DB 커넥션 대기 | `max(hikaricp_connections_pending{application="vium-be",env="prod"}) > 0` | 5m |
| Health 5xx 응답 | `sum(increase(http_server_requests_seconds_count{application="vium-be",env="prod",uri="/actuator/health",status=~"5.."}[5m])) > 0` | 2m |

5xx·p95는 위 기본 식에 최근 5분 최소 요청 수 조건을 추가한다. 쿼리 A는
`(<기본 비율 또는 p95 식>) and on() (sum(increase(http_server_requests_seconds_count{application="vium-be",env="prod",uri!~"/actuator.*"}[5m])) >= 20)`이며,
임계값은 별도 Threshold C에서 비교한다. 20은 초기 운영 기준으로, 표본 부족 시
결과가 없어 Normal이 된다. 저트래픽 장애를 놓칠 수 있으므로 트래픽에 맞춰 조정한다.
5xx 분자·분모, p95 버킷, 최소 요청 수에서 모두 `uri!~"/actuator.*"`로 Actuator를 제외한다.
이는 인증 실패 등 Actuator 이외의 모든 HTTP 요청을 포함하며, 성공한 업무 요청만을 뜻하지는 않는다.
새 `uri`·`status` 시리즈가 1에서 시작하면 첫 증가분은 `rate`/`increase`에 잡히지
않을 수 있다. 이 비율 규칙은 간헐적인 단일 500을 빠짐없이 검출하는 수단이 아니다.

실제 메트릭 이름·라벨은 Explore에서 확인하고 맞춘다. p95는 지정 버킷 기반
근사치다. 5xx가 아직 없거나 트래픽이 없으면 비율 식은 결과가 없거나 NaN일 수 있다.
이때 No Data를 정상으로 처리하고 수집 중단은 별도 끊김 규칙으로 확인한다.
끊김 규칙도 정상 수집 시 결과가 없으므로 No Data는 Normal로 둔다.
5분 조회 창과 5분 pending 때문에 끊김 감지는 마지막 수집 후 약 10분이 걸린다.

Health 5xx는 최소 사용자 요청 수 조건 없이 별도로 평가하므로 저트래픽 때도
반복되는 health 실패를 감지한다. 5분 창에 실패 증가분이 남아 있는 조건의
pending이 2분이라는 뜻이며, 연속 장애 2분을 요구하는 규칙은 아니다. 복구 후에도
조회 창 때문에 알림이 유지될 수 있다. 최초 5xx 시리즈의 첫 증가분 누락 가능성은
동일하며, HTTP 응답이 끝나지 않거나 메트릭 전송이 중단되면 이 규칙만으로는
감지할 수 없다. 수집 중단은 앱 메트릭 끊김 규칙으로 보완하며 외부 가용성은 [외부 가동 확인](#외부-가동-확인)에서 감시한다.

### 운영 설정과 복원

- 연락처: `vium-discord-daily-scrum` (Discord `daily-scrum`). 기본 알림 정책도
  이 연락처를 사용하며 복구 알림을 활성화했다. 웹훅 URL은 Grafana에만 저장한다.
- 규칙 9개의 실제 export: [alert-rules.yaml](../monitoring/grafana/alert-rules.yaml).
  폴더는 `Vium Production`, 평가 그룹은 `vium-prod-1m` (1분)이다.
  pending은 표의 지속 시간, **keep firing for는 0초**다.
- 활성 시리즈는 `grafanacloud-usage`에서 스택 `1896975`를 조회하며
  **8,000 초과, pending 5분**이다. 무료 계획 기준 10,000개의 80%이며
  Trial의 서비스 제한을 뜻하지 않는다. 플랜 변경 시 기준을 재검토한다.

규칙을 UI에서 변경하면 `vium-prod-1m` 그룹을 YAML로 다시 export해 함께 리뷰한다.
연락처는 비밀 값이 포함될 수 있으므로 export하지 않는다.
[Grafana의 export 형식](https://grafana.com/docs/grafana/latest/alerting/set-up/provision-alerting-resources/export-alerting-resources/)은
file provisioning용이며 **Grafana Cloud는 파일 provisioning을 지원하지 않는다**.
Cloud 복원은 연락처를 먼저 안전하게 재생성한 뒤, 이 파일을 기준으로
[HTTP provisioning API](https://grafana.com/docs/grafana/latest/alerting/set-up/provision-alerting-resources/http-api-provisioning/)
형식으로 변환하거나 Terraform을 사용한다. YAML을 API에 그대로 보내지 않는다.
새 스택에서는 데이터소스 UID·stack_id·폴더·조직 ID와 일시 중지 상태를 검토한다.
기본 알림 정책은 규칙 파일에 포함되지 않으므로 별도로 설정한다.
Synthetics 체크 `vium-api-health`는 이 export에 포함되지 않는다.
복원 시 [외부 가동 확인](#외부-가동-확인)의 설정으로 체크를 다시 생성하고
Job name을 유지한다. 체크 자체의 per-check alerts는 모두 끈다.

### 검증 기록 (2026-11-05)

편집 화면 Preview의 **쿼리 A 값**을 확인했다. Threshold C의 Normal 표시만으로
메트릭 존재를 판단하지 않는다. 이름·라벨 변경 시 같은 확인을 반복한다.

| 항목 | 실제 조회 결과 |
|---|---|
| 기존 Actuator 포함 p95 | 0.0475초. 업무 API 지연 검증값으로 사용하지 않는다 |
| 경로별 5분 증가분 | `uri="/actuator/health", status="200"` 10건, 나머지 관측 경로는 0건 |
| Actuator 제외 후 p95·5xx | 최소 요청 수 미달로 No Data, 정상 처리 |
| Health 5xx | 현재 5xx 시리즈가 없어 No Data, 정상 처리. 성공 health 시리즈로 URI·메트릭 이름 확인 |
| 힙 사용률 | 약 0.40626 |
| `jvm_memory_max_bytes{area="heap"}` | Eden 71,630,848 / Survivor 8,912,896 / Tenured 178,978,816 bytes; 모두 양수 |
| `hikaricp_connections_pending` 최댓값 | 0 |
| 활성 시리즈 | 610 |
| HTTP 카운터 | 200·400·401 시리즈 존재. 현재 5xx 비율은 No Data |

최근 7일 500 시리즈 조회에서는 `/api/me/waste-patterns`, `exception="none"`을
확인했으며 미존재 경로를 예외 라벨로 식별할 근거는 확보하지 못했다.
`@ExceptionHandler`로 처리된 예외가 자동으로 observation 오류에 기록된다는
보장은 없다([Spring 공식 설명](https://docs.spring.io/spring-framework/reference/integration/observability.html)).
따라서 `exception!="NoResourceFoundException"` 필터를 근거 없이 적용하지 않는다.
6개 규칙 이름을 넣은 Custom 연락처 테스트는 모두 Discord에서 수신했다.
힙 규칙 복제본(`Vium TEST - 발생 및 복구 경로 검증`)을 임계값 0, pending 0초로
설정해 실제 Firing 평가 → 기본 알림 정책 → Discord 전달을 확인했다.
임계값을 0.9로 되돌린 뒤 Normal 상태와 Discord Resolved 수신을 확인하고
임시 규칙을 삭제했다. 복구 메시지 사유는 `Updated`였다. 규칙 수정으로 인한
해결 알림까지 검증한 것이며, 고정된 규칙에서 메트릭이 임계값을 내려가는
자연 회복 시나리오까지 재현한 것은 아니다. 당시 임시 규칙은 export에서 제외했다.
이후 health 전용 규칙을 추가해 최종 export는 운영 규칙 7개다. Health 5xx 이름의
Custom 연락처 테스트도 Discord 수신을 확인했다. 실제 DB 장애·health 503은
유발하지 않았으며, 해당 장애 감지 자체를 실환경에서 재현한 테스트는 아니다.

## 외부 가동 확인

Grafana Cloud Synthetic Monitoring으로 외부 HTTPS health를 확인한다.
내부 앱 메트릭만으로 확인하기 어려운 DNS·TLS·Nginx 장애와 인증서 갱신 문제를 감지한다.

### 체크 설정

Testing & synthetics → Synthetics에서 HTTP 체크를 생성한다.

| 항목 | 값 |
|---|---|
| Job name | `vium-api-health` |
| Folder | `Grafana Synthetic Monitoring` |
| Target | `https://api.vium.site/actuator/health` |
| Method | GET |
| Probe locations | Seoul, Tokyo (AWS) |
| Frequency / Timeout | 120초 / 10초 |
| Valid status codes | 200만 허용 |
| Body 정규식 | `"status"\s*:\s*"UP"`, Invert 켬, Allow missing 끔 |
| SSL options | Fail if SSL is not present |
| Disable target certificate validation | 끔, 인증서 검증 유지 |
| Follow redirects | 끔 |
| IP version | IPv4. AAAA 레코드를 추가하면 Any 전환을 검토한다 |
| Publish full set of metrics | 끔 |
| Per-check alerts | Failed Checks·TLS Certificate·Latency 모두 끔 |

실행 시간은 timeout 10초로 실행당 1분 이내다. 30일 기준
`2 프로브 × 43,200분 / 2분 = 월 43,200회`이며 무료 API 실행 100k의 43.2%다.
31일 기준은 44,640회로, 체크 목록의 월 예상 실행 수와 같다.
[가격](https://grafana.com/pricing/)과
[실행 수 계산](https://grafana.com/docs/grafana-cloud/platform/pricing-and-usage/synthetic-monitoring/)을 참고한다.

### 알림 조건

데이터소스 UID는 `grafanacloud-prom`이다. 두 규칙은 `Vium Production` 폴더의
`vium-prod-1m` 그룹에서 1분마다 평가하고 `vium-discord-daily-scrum`으로 전송한다.
No Data는 Normal(`OK`), Error는 Error, keep firing for는 0초다.

| 이름 | PromQL 조건 | 지속 |
|---|---|---|
| 외부 HTTPS health 실패 | `(max by (job) (max_over_time(probe_success{job="vium-api-health"}[5m])) or on() vector(0)) < 1` | 1m |
| TLS 인증서 만료 임박 | `min by (job) ((last_over_time(probe_ssl_earliest_cert_expiry{job="vium-api-health"}[10m]) - time()) / 86400) < 14` | 10m |

쿼리 A는 Instant로 실행하고 비교식은 넣지 않는다. 임계값은 Threshold C에서 비교한다.
첫 행의 바깥 괄호는 `or` 전체 결과를 비교하기 위해 필요하다.

정상적인 2분 수집 주기에서는 5분 창에 프로브당 2~3회 실행이 들어간다.
두 프로브 모두 최근 5분 성공이 없어야 알림 조건이 성립하며, 1분 보류를 포함한
장애 감지는 약 6~8분이다. 어느 프로브든 성공 1회가 수집되면 다음 평가에서 복구한다.
실행 누락이 있으면 최소 실패 횟수를 보장하지 않는다. `or on() vector(0)`으로
체크 삭제·중지·메트릭 미수신도 감지한다. 의도적으로 체크를 멈출 때는 외부 health
규칙도 일시 중지한다. 한 프로브만 실패하거나 사라지고 다른 프로브가 성공하면 울리지 않는다.

인증서 규칙은 체인에서 가장 이른 만료 시각까지 남은 일수를 계산한다.
`last_over_time(...[10m])`으로 샘플 간격을 보완하고 프로브 중 가장 작은 값을 선택한다.
90일 인증서를 만료 30일 전에 갱신하는 구성에서 14일 미만은 갱신 시점 이후
약 16일이 지난 상태다. 갱신 실패 기간 자체를 측정하는 규칙은 아니므로
certbot 타이머·로그와 `sudo certbot renew --dry-run`으로 원인을 확인한다.
인증서 수명·certbot 버전·갱신 시점이 바뀌면 14일 기준을 재검토한다
([Certbot 갱신 안내](https://eff-certbot.readthedocs.io/en/stable/using.html#renewing-certificates)).
TLS 실패로 만료 메트릭이 사라지면 조회 창 이후 이 규칙은 No Data(Normal)가 되지만,
인증서 검증 실패는 외부 health 규칙으로 감지한다.

메트릭의 `job`은 Job name, `instance`는 Target URL, `probe`는 실행 위치,
`config_version`은 체크 설정 버전을 나타낸다. 체크 수정 시 시리즈가 갈리므로
알림은 `by (job)`으로 집계한다. 테스트에서 Target을 바꾸므로 `instance`로 필터하지 않는다.
[HTTP 체크](https://grafana.com/docs/grafana-cloud/observe-and-act/testing/synthetic-monitoring/create-checks/checks/http/)와
[공통 라벨](https://grafana.com/docs/grafana-cloud/observe-and-act/testing/synthetic-monitoring/create-checks/checks/)을 참고한다.

Per-check alerts는 사용하지 않는다. 폴더·평가 그룹·No Data 설정을 기존 규칙과
같게 관리하고 export 백업을 일원화하며 중복 알림을 방지하기 위해 직접 규칙을 만든다.
[Per-check alerts](https://grafana.com/docs/grafana-cloud/testing/synthetic-monitoring/configure-alerts/configure-per-check-alerts/)와
[Custom alerts](https://grafana.com/docs/grafana-cloud/observe-and-act/testing/synthetic-monitoring/configure-alerts/configure-custom-alerts/)를 참고한다.
공개 프로브 두 곳에서의 health 가용성만 확인하며 업무 API·로그인 흐름·응답 시간 SLO는 검증하지 않는다.

### 외부 가동 확인 검증 (2026-11-05)

| 항목 | 실제 조회 결과 |
|---|---|
| SM 메트릭 데이터소스 | 표시 이름 `grafanacloud-curioussedan286-prom`, export UID `grafanacloud-prom` |
| `probe_success` | `probe="Seoul"`, `probe="Tokyo"` 모두 1 |
| 라벨 | `job="vium-api-health"`, 정상 `instance="https://api.vium.site/actuator/health"`, 생성 직후 `config_version="179386713657479824"` |
| 인증서 남은 일수 | Preview 52.17573일, `< 14` 조건 Normal. notAfter 별도 조회는 미확인 |
| 체크 목록 | Enabled, 120초, 2 locations, 68 active series, 월 예상 44,640회 표시 |
| 활성 시리즈 증가 | 스택 Usage 전후 비교 미확인. 체크 목록의 68을 스택 증가량으로 간주하지 않는다 |
| 잘못된 경로 테스트 | 체크 변경 기록 17:36:53, 약 17:42 쿼리 A가 0, 약 17:43 상단 Firing 확인 |
| 원복과 복구 | Target 원복 후 그래프에서 약 17:44에 A가 1로 복귀하고 상단 Normal 확인 |
| Discord | 사용자가 Firing·Resolved 모두 수신 확인. 정확한 수신 시각과 원복 저장 시각은 미기록 |
| TLS 연락처 테스트 | 미실시. TLS 규칙 이름을 넣은 Custom 테스트의 Discord 수신 확인 필요 |

시각은 제공된 Grafana 화면 기준이며 그래프에서 읽은 분 단위 시각은 근사값이다.
체크 Target만 `/actuator/health-wrong`으로 바꾼 뒤 `/actuator/health`로 복원했다.
앱·Nginx·DNS·인증서 장애는 유발하지 않았다. 알림 규칙을 수정하지 않고
메트릭 회복으로 Normal 전환과 Discord Resolved 수신을 확인했다.
인증서 규칙은 Preview의 실제 남은 일수와 조건을 확인했으며 실제 만료는 유발하지 않았다.
연락처 테스트는 Alerting → Contact points → `vium-discord-daily-scrum` → Test → Custom에서
`Vium prod - TLS 인증서 만료 임박`을 넣어 수행하고 수신 결과를 기록한다.

## 사용량과 보안

Synthetics 무료 한도는 월 API 실행 100k이며 이 체크는 30일 43,200회, 31일 44,640회를 사용한다.
체크·프로브·주기를 바꾸기 전에 실행 수를 다시 계산한다.

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
