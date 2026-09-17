# CLAUDE.md — PickDo (비움) 백엔드 개발 지침

이 파일은 Claude Code가 이 프로젝트에서 작업할 때 항상 참조하는 지침서다.
작업 전 이 문서와 함께 아래 스펙 파일들을 반드시 읽고 시작한다.

- `docs/PickDo.openapi.json` — API 명세 (요청/응답/필드/타입의 진실의 원천)
- `docs/vium.sql` — 데이터베이스 스키마 (테이블·컬럼·관계·제약)
- `docs/기능명세서.csv` — 기능 명세 (기능별 분류·설명·우선순위)

> **중요 — 파일이 없으면 먼저 요청할 것.**
> 위 파일 중 하나라도 전달받지 못했거나 프로젝트에서 찾을 수 없으면,
> **임의로 추측해서 코드나 스키마를 만들지 말고, 먼저 사용자에게 해당 파일을 달라고 요청한다.**
> 예: "작업을 시작하기 전에 `docs/vium.sql`이 필요합니다. 파일을 전달해 주세요."
> 파일이 모두 확보되기 전에는 실제 구현(엔드포인트·엔티티·마이그레이션 작성)을 시작하지 않는다.
> 단, 어떤 파일이 필요한지 안내하거나 개발 계획    을 설명하는 것은 파일 없이도 할 수 있다.

---

## 1. 프로젝트 개요

**PickDo(비움)**: 자취생·1인 가구가 식재료를 사놓고 버리는 낭비를 줄이도록,
"무엇을 얼마나 버리는지"를 거의 자동으로 추적·분석해 다음 장보기에서 덜 사게 돕는 서비스.

지금 개발 대상은 **백엔드 REST API**다.

---

## 2. 기술 스택

- 언어/프레임워크: **Spring Boot 3.x (Java 17+)**
- 빌드: Gradle (Kotlin DSL 또는 Groovy)
- DB: **PostgreSQL**
- 접근: Spring Data JPA (+ 필요 시 QueryDSL 또는 native query for 집계)
- 마이그레이션: Flyway (스키마 버전 관리)
- 문서: 기존 OpenAPI 명세(`docs/PickDo.openapi.json`)를 진실의 원천으로 삼는다.

> 최신 버전/문법은 학습 시점 이후 바뀌었을 수 있으니, 애매하면 공식 문서를 함께 확인하고 그 사실을 알린다.

---

## 3. 설계 원칙 (이 서비스의 정체성 — 반드시 지킬 것)

1. **입력 최소화.** 사용자가 손으로 하는 일을 최대한 줄인다. 등록은 간단히, 폐기 기록은 자동 추정으로 대체.
2. **낭비 데이터는 이벤트에서 나온다.** 모든 소진/폐기는 `consumption_events`에 이벤트로 남고, 집계·패턴·구매제안은 전부 이 이벤트에서 계산한다. **status만 바꾸고 이벤트를 안 남기면 안 된다.**
3. **"반성 도구"가 아니라 "장보기 도우미".** 응답·기능 설계 시 사용자를 탓하는 톤이 아니라 돕는 방향으로.
4. **위험 품목은 입력이 아니라 출력.** 자주 버리는 품목은 사용자에게 미리 묻지 말고, 쌓인 폐기 데이터에서 자동 발견한다.
5. **AI는 보조 수단.** OCR·태깅·레시피 조합에만 쓰고, 핵심 로직(추적·집계·제안)은 규칙 기반으로 구현한다.

---

## 4. 개발 순서와 현재 진행 상태

기본 개발 계획은 데이터가 "생기고 → 흐르고 → 분석되는" 순서다. 사용자 요청에 따라 이메일 로그인과 Access Token 인증은 먼저 구현했으며, 현재 인증 상태는 5항을 따른다.

1. **[먼저] 프로젝트 뼈대 + DB 마이그레이션**
    - Spring Boot 프로젝트 구성, PostgreSQL 연결, Flyway로 `docs/vium.sql` 기반 스키마 생성.
    - 코드 테이블(units, storage_methods, item_statuses, event_sources 등) 시드 데이터 삽입.
2. **재료 등록/조회** — `POST /api/me/ingredients`, `GET /api/me/ingredients`
    - 등록 시 `expiry_estimation_rules`를 참조해 `expires_on` 자동 추정(사용자 수정 가능).
    - 조회는 소비기한 임박순 정렬.
3. **소진/폐기 처리 (핵심)** — `PATCH /api/me/ingredients/{ingredientId}/status`
    - status를 consumed/disposed로 바꿀 때 **consumption_events에 이벤트 1건 자동 적재**.
    - disposed면 wasteQuantity/wasteAmount를 함께 이벤트에 기록.
4. **낭비 집계/패턴** — `GET /api/me/waste-patterns`, `GET /api/me/waste-reports`
    - `waste_reports` 같은 별도 테이블에 미리 만들어두지 않고, `consumption_events`를 기간·카테고리별로
      **요청 시점에 집계**해서 반환한다. 자주 버리는 위험 품목도 이 집계에서 자동 발견.
5. **장보기 도우미 / 구매량 제안** — shopping-helper, purchase-suggestions, shopping-list-items
6. **알림** — notifications (서버 배치가 생성)
7. **인증** — 이메일 로그인과 Access Token 인증 구현 완료. 회원가입·토큰 갱신·로그아웃은 후속 작업이다. (아래 5항 참고)
8. **확장** — 영수증 OCR(scan, `purchases`에 저장), 레시피 추천, 대시보드/절약금액.

---

## 5. 인증 구조와 현재 구현 범위

- `POST /api/auth/login`에서 이메일·BCrypt 비밀번호를 검증하고 Access Token과 Refresh Token을 발급한다.
- Access Token은 HS256 JWT이며, Spring Security Resource Server와 `NimbusJwtDecoder`로 서명·발급자·토큰 종류(`access`)·양수 Long 사용자 ID·시간 조건을 검증한다.
- `JWT_SECRET`은 Base64로 인코딩된 32바이트 이상의 비밀키로 필수 설정한다. 기본 유효기간은 Access Token 1시간, Refresh Token 14일이다.
- `JWT_CLOCK_SKEW_SECONDS`는 발급·만료·사용 시작 시각 검증의 시간 오차 허용치다. 기본 60초이며 0~300초만 허용한다.
- 공개 경로는 `POST /api/auth/login`, `GET /api/health`, `GET /actuator/health` 및 그 하위 경로다. 나머지 요청에는 인증이 필요하다. HTTP 세션에 인증을 저장하지 않는 stateless 방식이다.
- 현재 사용자 ID는 `CurrentUserProvider.getCurrentUserId()`가 `SecurityContext`의 검증된 JWT에서 가져온다. 고정 사용자 ID를 사용하지 않는다. 컨트롤러는 이 값을 서비스에 전달한다.
- 인증 실패는 `UNAUTHORIZED(401)`, 접근 거부는 `FORBIDDEN(403)`을 공통 응답 형식으로 반환한다. 사용자별 데이터 소유권 검사는 기존 서비스·저장소에서 수행한다.
- Refresh Token은 `SecureRandom`으로 만든 32바이트 난수의 Base64 URL 문자열이다. `user_sessions`에는 원문 대신 SHA-256 해시를 저장한다.
- 세션의 기존 `timestamp` 컬럼에는 UTC 기준 `LocalDateTime`을 저장하고, 만료 비교는 `UserSession.isExpired(Instant)`를 사용한다. 기존 재고·소진 이벤트의 서버 기본 시간대 사용까지 통일한 상태는 아니다.
- **미구현:** 회원가입, 토큰 갱신, 로그아웃, 세션 폐기 및 만료 세션 정리. `isExpired()`는 준비된 비교 메서드이며 아직 프로덕션 호출자는 없다. 새 환경의 로그인에는 BCrypt 비밀번호가 저장된 계정이 필요하며, 회원가입은 별도 이슈로 구현한다.

---

## 6. API / 데이터 규칙

- **응답 래퍼:** 모든 응답은 `{ success, data, error }` 형태. 성공 시 error=null, 실패 시 data=null + error{code,message}.
- **에러:** 공통 에러 응답(ErrorResponse) 형태를 지킨다. 예외를 `@RestControllerAdvice`로 잡아 표준 형태로 변환.
- **id:** 정수(bigint / Long).
- **수량(quantity 계열):** 소수 허용 → Java `BigDecimal`, DB `numeric(12,3)`.
- **금액(amount 계열):** 원 단위 정수 → API는 integer. (DB numeric이면 소수 0자리로 맞춤)
- **시간:** DB는 timestamptz 권장, Java는 `OffsetDateTime`/`Instant`.
- **소프트 삭제:** users 등 deleted_at 사용 시 unique(email)는 "deleted_at IS NULL" 부분 유니크 인덱스로.
- **재료 식별:** inventory_items는 `ingredient_catalog_id` 또는 `custom_name` 중 최소 하나가 반드시 존재해야 한다(둘 다 null 금지). 서비스 레벨 또는 DB CHECK로 강제.

---

## 7. 아키텍처 / 코드 구조

### 7.1 기본 구조와 도메인 책임

- **도메인별 모놀리식 + 계층형 아키텍처**를 사용한다. 하나의 Spring Boot 애플리케이션과 PostgreSQL DB를 운영한다.
- `dispose`는 소진·폐기, 자동 폐기, 이벤트 이력, 낭비 리포트·패턴 집계를 담당한다.
- `ingredient`는 공용 재료 카탈로그·소비기한 규칙, `inventory`는 사용자별 보유 재료·잔여 수량·상태, `user`는 사용자 정보·설정을 담당한다.
- `auth`는 로그인 흐름·토큰 발급·세션 저장을 담당한다. `user`는 계정 조회와 비밀번호 검증을 담당하며, `auth`에는 향후 회원가입·토큰 갱신·로그아웃 흐름을 추가한다.

**큰 구조와 규칙은 먼저 합의하고, 구체적인 패키지·클래스는 기능 개발에 맞춰 추가한다.**

- 도메인별 책임, 계층별 역할, 도메인 간 접근 방식, 트랜잭션 경계, 인증·예외·응답 처리 기준은 개발 전에 정한다.
- 새 기능을 개발할 때 기존 도메인에서 담당할 수 있는지 먼저 확인하고, 필요한 패키지와 클래스만 추가한다. 아래 구조도는 현재 구현 상태이며 앞으로 추가할 도메인을 제한하지 않는다.
- 사용하지 않는 클래스나 모든 계층의 빈 폴더를 일괄 생성하지 않는다. 기능마다 별도의 계층 구조를 도입하지 않고 이 문서의 공통 규칙을 따른다.
- 팀이 전체 구성과 역할을 공유하기 위해 빈 패키지를 미리 두는 것은 허용한다. 기존 빈 패키지를 사용하지 않는다는 이유만으로 일괄 삭제하지 않는다.
- 기능이 구체화되며 구조 조정이 필요하면 관련 도메인의 책임과 의존성을 검토하고, 합의된 변경을 이 문서에 반영한다.

현재 구조는 다음과 같다. 각 도메인은 실제 필요한 계층만 둔다.

```text
com.vium
├── ingredient
│   ├── service
│   ├── entity
│   └── repository
├── inventory
│   ├── controller
│   ├── dto
│   ├── service          InventoryService
│   ├── entity           InventoryItem
│   └── repository       InventoryItemRepository, InventoryQueryRepository
├── dispose
│   ├── controller       DisposeController
│   ├── dto              소진·폐기 및 리포트 요청/응답
│   ├── service          DisposeService, AutoDisposeService, WasteReportService
│   ├── entity           ConsumptionEvent
│   └── repository       ConsumptionEventRepository,
│                        ConsumptionEventQueryRepository, WasteReportQueryRepository
├── auth
│   ├── controller       AuthController
│   ├── dto              LoginRequest, LoginResponse
│   ├── service          AuthService, TokenService
│   ├── entity           UserSession
│   └── repository       UserSessionRepository
├── user
│   ├── dto              UserIdentity
│   ├── service          UserSettingsService, UserLoginService
│   └── repository       UserSettingsRepository, UserCredentialRepository
└── global
    ├── common           ApiResponse
    ├── exception        BusinessException, ErrorCode, GlobalExceptionHandler 등
    ├── security         SecurityConfig, CurrentUserProvider, TokenConfig, JwtProperties
    ├── code             공통 코드 테이블 Entity·Repository
    └── controller       HealthController
```

### 7.2 계층별 역할

- 기본 호출 흐름은 **Controller → Service → Repository**다. `presentation/application/*UseCase` 구조와 혼용하지 않는다.
- `controller`: HTTP 요청 바인딩·검증, 현재 사용자 확인, 서비스 호출, 응답 반환. DB에 직접 접근하지 않는다.
- `service`: 업무 흐름 조율, 사용자 소유권 확인, 트랜잭션 경계 설정. SQL과 JDBC 결과 매핑을 작성하지 않는다.
- `entity`: 데이터와 해당 데이터의 수량·상태 변경 규칙을 관리한다.
- `repository`: DB 조회·저장과 조회 결과 매핑을 담당한다.
- `dto`: 요청·응답 및 서비스 간 전달 데이터를 정의한다. 엔티티를 API 응답으로 직접 노출하지 않는다.
- 불필요한 인터페이스/구현체 쌍이나 별도 어댑터 계층을 일괄 추가하지 않는다.

### 7.3 도메인 간 의존성과 DB 접근

- 다른 도메인의 Repository를 직접 주입하지 않고, 해당 도메인의 Service를 통해 접근한다. 공통 코드 테이블은 `global.code`의 Repository를 사용할 수 있다.
- 특히 **다른 도메인의 데이터를 변경할 때는 소유 도메인의 Service를 반드시 거친다.** `dispose`의 재고 변경은 `InventoryService`에 위임한다.
- 도메인 사이에 변경 가능한 엔티티를 전달해 외부에서 수정하지 않는다. 필요한 정보는 `InventoryState` 같은 DTO로 전달한다.
- 서비스 간 순환 의존성을 만들지 않는다. `dispose → inventory` 호출은 가능하지만 `inventory → dispose` 역호출은 만들지 않는다.
- 저장과 일반 조회는 JPA를 기본으로 사용한다. 복합 조회·집계는 전용 `*QueryRepository`에서 JDBC 또는 native SQL로 처리할 수 있다.
- **읽기 전용 QueryRepository는 여러 도메인의 테이블을 조인할 수 있다.** 조회를 위해 항목마다 다른 서비스를 반복 호출하지 않는다. 이 허용은 다른 도메인의 데이터를 직접 변경하는 권한을 의미하지 않는다.

### 7.4 트랜잭션과 현재 사용자

- 함께 성공해야 하는 업무의 최상위 Service 메서드에 `@Transactional`을 둔다.
- 소진·폐기는 `DisposeService → InventoryService`로 재고를 변경한 뒤 `ConsumptionEventRepository`에 이벤트를 저장한다. 자동 폐기는 `AutoDisposeService`가 같은 원칙으로 조율한다.
- **재고 변경과 이벤트 저장은 동일 트랜잭션에 참여하며, 하나라도 실패하면 모두 롤백한다.** 재고 변경에 별도 `REQUIRES_NEW` 트랜잭션을 사용하지 않는다.
- 조회 서비스에는 `@Transactional(readOnly = true)`를 적용한다.
- 현재 사용자 ID는 `global.security.CurrentUserProvider` 한 곳에서 제공한다. 컨트롤러는 이를 주입받고, 서비스에 사용자 ID를 전달한다. `AuthenticationUtil` 같은 중복 접근 경로를 만들지 않는다.
- `auth`는 로그인·토큰 발급·세션 저장을, `global.security`는 요청 인증 검증과 현재 사용자 접근을 담당한다.
- 로그인은 `AuthService → UserLoginService`로 자격 증명을 검증하고 `UserIdentity` DTO를 받는다. `AuthService`의 트랜잭션에서 세션을 저장하며, 저장 실패 시 토큰을 응답하지 않는다.

### 7.5 리팩토링 범위와 검증

- 구조 변경만으로 기존 API 경로·요청/응답·DB 테이블/컬럼을 변경하지 않는다. Java 도메인 이름과 DB 테이블 이름은 같을 필요가 없다. `dispose`에서도 `consumption_events`를 그대로 사용한다.
- 이미 적용된 Flyway 마이그레이션은 수정하지 않는다. 실제 스키마 변경이 필요할 때는 별도 마이그레이션을 추가한다.
- 상태값은 enum 또는 코드 테이블 참조를 기준으로 다룬다.
- 구조 변경과 기존 기능 버그 수정은 구분해 진행한다.
- 변경 후 기존 테스트와 주요 회귀 테스트를 실행한다. 사용자별 접근 제한, 재고 변경·이벤트 저장의 원자성, API 응답 호환성을 확인한다.
- 커밋은 기능 단위로 작게 유지하고 각 단계 완료 시 동작하는 상태를 유지한다.

---

## 8. 작업 시 지켜야 할 것

- **한 번에 하나의 단계**만 구현하고, 끝나면 무엇을 만들었는지 요약한다.
- API를 만들 때는 반드시 `docs/PickDo.openapi.json`의 해당 엔드포인트 정의(요청/응답/필드 타입)를 따른다. 명세와 다르게 구현해야 할 이유가 생기면 먼저 알리고 확인을 받는다.
- DB 스키마(`docs/vium.sql`)와 어긋나는 테이블·컬럼을 임의로 만들지 않는다. 변경이 필요하면 이유를 설명하고 마이그레이션으로 처리한다.
- 소진/폐기 로직을 구현할 때 **consumption_events 적재를 빠뜨리지 않는다**(설계 원칙 2).
- 테스트: 각 API에 대해 최소한의 통합 테스트(happy path + 주요 예외)를 작성한다.
- 불확실하거나 설계 판단이 필요한 지점은 임의로 결정하지 말고 질문한다.

---

## 9. 참고 파일

- `docs/PickDo.openapi.json` — API 명세(진실의 원천).
- `docs/vium.sql` — DB 스키마(테이블·컬럼·관계·제약)의 진실의 원천.
- `docs/기능명세서.csv` — 기능 명세(분류·설명·우선순위).

---

## 10. 이슈 / 브랜치 / PR 워크플로우

1. **이슈부터 만든다.** GitHub "New issue"에서 종류에 맞는 템플릿(`.github/ISSUE_TEMPLATE/`)을 골라 작성한다.
   - ✨ 기능 개발 → `feature.yml`
   - 🐛 버그 리포트 → `bug.yml`
   - 🔧 리팩토링/문서/테스트/설정 → `chore.yml`
2. **이슈 번호 기준으로 브랜치를 판다.** 이름 규칙: `<type>/<이슈번호>-<짧은-설명>`
   - `type`은 커밋 타입과 동일: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`
   - 예: `feat/12-ingredient-register`, `fix/27-waste-report-timezone`
3. **커밋 메시지도 같은 타입 접두사를 쓴다.** 예: `feat: 재료 등록 API 구현 (#12)`
4. **PR을 올릴 때는 `Closes #이슈번호`로 연결한다.** (PR 템플릿에 이미 자리 있음 → 머지 시 이슈 자동 닫힘)
