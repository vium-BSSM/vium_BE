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

## 4. 개발 순서 (이 순서대로 진행한다)

데이터가 "생기고 → 흐르고 → 분석되는" 순서를 따른다.

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
7. **인증(로그인/회원가입)** — 이 단계에서 마지막에 얹는다. (아래 5항 참고)
8. **확장** — 영수증 OCR(scan, `purchases`에 저장), 레시피 추천, 대시보드/절약금액.

---

## 5. 인증에 대한 임시 규칙 (중요)

- **인증은 마지막에 구현한다.** 초반에는 만들지 않는다.
- 그 전까지는 현재 사용자 id를 **한 곳에서만** 가져오도록 유틸/컴포넌트로 분리한다.
    - 예: `CurrentUserProvider.getCurrentUserId()` → 지금은 고정값(예: 1L) 반환.
    - 나중에 인증을 붙이면 이 메서드 내부만 "JWT에서 추출"로 교체한다.
- 컨트롤러·서비스 어디에도 user_id를 하드코딩하지 말고 반드시 이 유틸을 거친다.
- API 명세상 전역 보안은 Bearer(JWT)로 되어 있으나, 실제 필터 적용은 7단계에서 한다.

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

## 7. 코드 컨벤션 / 구조

- **패키지 구조: 기능별(package-by-feature).** `com.vium` 밑에 도메인별로 최상위 패키지를 두고,
  그 안에서만 `controller → service → repository` 계층(+ `dto`, `entity`)을 나눈다.
  전역 `controller`/`service` 폴더에 몰아넣지 않는다.
  - `global` — `config`, `common`(`ApiResponse`, `ErrorCode`, `BusinessException`, `GlobalExceptionHandler`),
    `auth`(`CurrentUserProvider`), `code`(units/storage_methods/item_statuses/event_sources/
    purchase_sources/ingredient_categories 같은 코드 테이블), `presentation`(HealthController)
  - `user`, `ingredient`, `inventory`, `consumption`, `receipt`, `report`, `shopping`, `notification`, `recipe`
- **도메인 간 접근은 Repository 직접 참조 금지, 반드시 상대 도메인의 Service를 거친다.**
  (예: 소진/폐기 처리 시 재고 차감은 `InventoryService`를 통해서만.)
- 컨트롤러는 얇게, 비즈니스 로직은 서비스에.
- DTO와 엔티티를 분리한다(엔티티를 그대로 응답에 노출하지 않는다).
- 상태값(status, event_type 등)은 문자열 남발 대신 enum 또는 코드 테이블 참조로 다룬다.
- 커밋은 기능 단위로 작게. 각 단계 완료 시 동작하는 상태를 유지한다.

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
