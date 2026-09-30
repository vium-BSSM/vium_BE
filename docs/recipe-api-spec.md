# PickDo(비움) 레시피 API 명세서

> 대상 화면: `recipe` · `recipe/detail` · `recipe/detail/add` · `recipe/detail/delete`
> 추천 방식: DB의 AI 레시피 재사용 + 모자란 만큼 Gemini(Vertex AI, API 키)로 생성 / 이미지: Unsplash 검색
> 스택: Spring Boot(Java) · PostgreSQL

---

## 0. 기존 코드 확인 결과 (1단계 완료)

기존 코드를 확인한 결과입니다. **이 문서의 이름·값은 아래 실제 값을 기준으로 합니다.** 이 표에 없는 규칙은 기존 코드·CLAUDE.md의 컨벤션을 따릅니다.

| # | 항목 | 실제 값 |
|---|---|---|
| 1 | 공통 응답 래퍼 | `{ success, data, error: { code, message } }` (명세 가정과 일치) |
| 2 | 에러 코드 | 도메인별 enum 분리 (`RecipeErrorCode` 등, CLAUDE.md 아키텍처 기준). 추가 코드는 5-3·2-3·3-3 참고. 재고 관련 코드(`INVENTORY_*`)가 재고 도메인에 이미 있으면 **재사용** |
| 3 | 로그인 사용자 | 기존 방식 사용 (명세 가정과 일치). `/api/me/**` 인증 필수 |
| 4 | 재고 엔티티·테이블 | `InventoryItem` / `inventory_items` (`com.vium.inventory.entity`) |
| 5 | 재고 상태 | `item_statuses.code`: `active`(보유중), `consumed`(소진), `disposed`(폐기). Java: `ItemStatus` (`com.vium.global.code`). 서비스에는 `Short statusId`로 전달 |
| 6 | 남은 양 | `remainingQuantity` — `BigDecimal` / `numeric(12,3)`, **수량**. 컬럼 추가 없음 |
| 7 | 소비기한 | `expiresOn` — `LocalDate` / `DATE`, nullable |
| 8 | 재료 마스터 | `IngredientCatalog` / `ingredient_catalog` (`com.vium.ingredient.entity`), 이름 컬럼 `name` (varchar(120)) |
| 9 | 소진·폐기 처리 | `DisposeService.updateStatus(Long userId, Long inventoryItemId, UpdateInventoryItemStatusRequest request)` (`@Transactional`) → 내부에서 `InventoryService.updateStatus(Long userId, Long inventoryItemId, BigDecimal quantity, Short statusId)` 호출 + `ConsumptionEvent` 저장 |
| 10 | 재료 목록 조회 API | 기존 API 재사용 (명세 가정과 일치) |
| 11 | 스키마 관리 | 기존 마이그레이션 방식 사용 (명세 가정과 일치). 부록 A는 새 마이그레이션 파일로 추가 |
| 12 | 기존 데이터 | `recipes`, `recipe_suggestions` 모두 데이터 없음 → `NOT NULL` 컬럼 추가 시 기본값 불필요 |

**추가 확인 결과 (5-4에 반영)**
- `InventoryService.updateStatus`의 `quantity`는 **사용한 양** (`remainingQuantity.subtract(quantity)`)
- `statusId`는 남은 양과 상관없이 **무조건 반영** → 부분 사용에는 사용 불가 → 5-4 방식 B로 확정

---

## 1. 개요

### 1-1. 화면별 API

| # | 화면 | 기능 | Method | Endpoint | 구분 |
|---|---|---|---|---|---|
| 1 | recipe | 맞춤 레시피 목록 (AI 추천) | GET | `/api/me/recipes/recommended` | 신규 |
| 2 | recipe/detail | 레시피 상세 | GET | `/api/me/recipes/{recipeId}` | 신규 |
| 3 | recipe/detail/add | 요리에 추가할 재료 목록 | GET | 기존 재료 목록 조회 API | 재사용 (구현 없음) |
| 4 | recipe/detail/delete | 요리 완료 · 재료 사용량 반영 | POST | `/api/me/recipes/{recipeId}/complete` | 신규 |

### 1-2. 화면 흐름

```
[recipe] 목록 ──카드 클릭──▶ [recipe/detail] 상세
                                 │  ├─ "추가" 클릭 ──▶ [recipe/detail/add] 재료 선택 ──"추가"──▶ 상세로 복귀
                                 │  └─ "다 만들었어요!" 클릭
                                 ▼
                          [recipe/detail/delete] 사용량 조사 ──"완료"──▶ 재고 차감
```

### 1-3. 공통 사항

**인증:** 모든 API는 로그인 필수입니다. 사용자 ID는 인증 정보에서 가져오고, 요청으로 받지 않습니다.

```
Authorization: Bearer {accessToken}
```

**성공 응답**

```json
{ "success": true, "data": { }, "error": null }
```

**실패 응답**

```json
{
  "success": false,
  "data": null,
  "error": { "code": "RECIPE_NOT_FOUND", "message": "레시피를 찾을 수 없습니다." }
}
```

**공통 에러**

| HTTP | code | 설명 |
|---|---|---|
| 401 | `UNAUTHORIZED` | 토큰 없음 / 만료 |
| 500 | `INTERNAL_SERVER_ERROR` | 서버 내부 오류 |

**날짜 기준:** "오늘", "D-day" 계산은 모두 **Asia/Seoul(KST)** 기준입니다.

### 1-4. Enum — `RecipeCategory`

| 값 | 화면 탭 | DB 저장 |
|---|---|---|
| `ALL` | 전체 | ✕ (요청 필터 전용) |
| `KOREAN` | 한식 | ○ |
| `CHINESE` | 중식 | ○ |
| `WESTERN` | 양식 | ○ |
| `JAPANESE` | 일식 | ○ |
| `DESSERT` | 디저트 | ○ |

- 탭 순서: 전체 · 한식 · 중식 · 양식 · 일식 · 디저트 (기존 UI에서 "한식"이 두 번 들어간 오류를 중식으로 수정)
- 요청 값은 **대문자만** 받습니다. 그 외 값은 `400 INVALID_CATEGORY`입니다.
- Java에서는 `ALL`을 포함한 enum 하나로 쓰고, 엔티티에 `ALL`이 저장되지 않게 검증합니다.

---

## 2. API ① 맞춤 레시피 목록 (AI 추천)

**화면:** `recipe` — 카테고리 탭 + 카드 목록 (제목, "조리시간 20분", "#당근 #감자 #무지방 우유")

```
GET /api/me/recipes/recommended
```

### 2-1. Request

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|---|
| Query | `category` | RecipeCategory | N | `ALL` | 카테고리 탭 필터 |

```
GET /api/me/recipes/recommended?category=WESTERN
```

### 2-2. Response `200 OK`

| 필드 | 타입 | Null | 설명 |
|---|---|---|---|
| `recipes` | Array | ✕ | 추천 레시피 목록. 없으면 `[]` |
| `recipes[].recipeId` | Long | ✕ | 레시피 ID |
| `recipes[].title` | String | ✕ | 카드 제목 |
| `recipes[].category` | RecipeCategory | ✕ | `ALL` 제외 |
| `recipes[].cookTime` | Integer | ✕ | 조리시간(분) |
| `recipes[].usedIngredients` | Array | ✕ | 카드 해시태그. `recipe_ingredients` 전체 |
| `recipes[].usedIngredients[].ingredientCatalogId` | Long | ✕ | 재료 마스터 ID |
| `recipes[].usedIngredients[].name` | String | ✕ | 재료 이름 |

```json
{
  "success": true,
  "data": {
    "recipes": [
      {
        "recipeId": 1,
        "title": "당근 크림 파스타",
        "category": "WESTERN",
        "cookTime": 20,
        "usedIngredients": [
          { "ingredientCatalogId": 3, "name": "당근" },
          { "ingredientCatalogId": 7, "name": "감자" },
          { "ingredientCatalogId": 5, "name": "무지방 우유" },
          { "ingredientCatalogId": 11, "name": "파스타면" }
        ]
      },
      {
        "recipeId": 2,
        "title": "감자 우유 수프",
        "category": "WESTERN",
        "cookTime": 25,
        "usedIngredients": [
          { "ingredientCatalogId": 7, "name": "감자" },
          { "ingredientCatalogId": 5, "name": "무지방 우유" }
        ]
      }
    ]
  },
  "error": null
}
```

- `category = ALL`이면 최대 15개 (카테고리 5개 × 3개), 특정 카테고리면 최대 3개입니다.
- `usedIngredients` 순서: `recipe_ingredients.id` 오름차순 (LLM이 준 순서)

### 2-3. Error

| HTTP | code | 상황 |
|---|---|---|
| 400 | `INVALID_CATEGORY` | enum에 없는 값 (enum 바인딩 실패 예외를 이 코드로 변환) |
| 503 | `RECIPE_GENERATION_FAILED` | 재사용 레시피가 하나도 없고, LLM 호출도 재시도 후 실패 (사용 한도 초과 `429` 포함) |

> 재사용 레시피가 1개라도 있으면 LLM이 실패해도 재사용 레시피만 반환합니다 (`200`). LLM이 정상 응답했는데 만들 수 있는 레시피가 0개인 경우도 `200`, `[]`입니다.

### 2-4. 처리 로직

**핵심 규칙**

| 규칙 | 내용 |
|---|---|
| 개수 | 카테고리당 3개, 전체 15개 |
| 갱신 | 사용자의 **재료 구성이 바뀌었을 때만** 새로 추천 (재료 추가·소진·폐기) |
| 재사용 | DB에 이미 있는 AI 레시피 중 **사용자가 가진 재료만으로 만들 수 있는 것**을 먼저 쓰고, 모자란 개수만 LLM으로 생성 |

```
1. 사용자 `active` 재고 조회
   └─ 0개 → [] 반환 (저장·LLM 호출 없음)
2. 재료 구성 해시 계산
3. 이 사용자의 가장 최근 추천 묶음(batch)의 해시와 같은가?
   ├─ 같음 → 7번으로 (재사용·LLM 호출 없음)
   └─ 다름 / 추천 기록 없음 → 4번
4. a. 카테고리 5개 각각: DB에서 재사용할 레시피를 찾아 점수 순으로 최대 3개
   b. 3개가 안 되는 카테고리들의 모자란 개수를 모아서 LLM 1회 호출로 생성 → 검증
5. 새로 생성한 레시피만 Unsplash 이미지 검색          ← 3~5번은 트랜잭션 밖
6. 저장 (새 레시피 + 이번 묶음 추천 기록)              ← 하나의 트랜잭션
7. 최근 묶음의 레시피 조회 → category 필터 → 점수 정렬 → 반환
```

**1) 재고 조회**
- 대상: 사용자의 `active` 재고 (`inventory_items`)
- 같은 `ingredient_catalog_id`가 여러 건이면 1건으로 합치고, 소비기한은 가장 빠른 날짜를 씁니다.
- `expireInDays` = 소비기한 − 오늘(KST). 소비기한이 없으면 `null`, 이미 지났으면 음수.

**2) 재료 구성 해시**
- 보유 재료의 `ingredient_catalog_id`를 중복 제거 → 오름차순 정렬 → `","`로 연결 → SHA-256 (hex 64자)
- 예: `[3, 5, 7, 11]` → `"3,5,7,11"` → SHA-256
- 남은 양이나 소비기한이 바뀌는 것은 해시에 영향을 주지 않습니다. 재료가 **생기거나 없어질 때만** 바뀝니다.

**3) 캐시 확인**
- `recipe_suggestions`에서 이 사용자의 행을 `suggested_at` 내림차순으로 1건 조회 → 그 행의 `batch_id`, `inventory_hash`
- `inventory_hash`가 2)의 해시와 같으면 그 `batch_id`의 레시피를 그대로 씁니다.
- 동시 요청으로 묶음이 두 번 생기는 경우는 MVP에서 허용합니다 (가장 최근 묶음만 사용).

**4-a) 재사용 레시피 찾기** (카테고리별)
- 조건: `source = 'AI'`, 해당 `category`, **모든 재료가 사용자 보유 재료 안에 있음**

```sql
SELECT r.*
FROM recipes r
WHERE r.source = 'AI'
  AND r.category = :category
  AND NOT EXISTS (
    SELECT 1
    FROM recipe_ingredients ri
    WHERE ri.recipe_id = r.id
      AND ri.ingredient_catalog_id <> ALL (:userCatalogIds)
  )
ORDER BY r.created_at DESC
LIMIT 100;
```

- 가져온 후보를 7)의 **긴급도 점수**로 정렬해서 상위 3개를 고릅니다. 점수가 0인 레시피(임박한 재료를 하나도 안 쓰는 것)도 포함할 수 있습니다.
- 모든 레시피는 재료가 1개 이상이어야 합니다 (4-b 검증). 재료가 0개인 레시피는 `NOT EXISTS` 조건을 항상 통과하기 때문입니다.

**4-b) LLM 생성** (모자란 카테고리만, **1회 호출**) — 프롬프트는 [부록 B](#부록-b-llm-연동)
- 카테고리별 `need = 3 − 재사용 개수`. 모든 카테고리가 `need = 0`이면 호출하지 않습니다.
- 모자란 카테고리를 **한 번의 호출에 모아서** 요청합니다. 호출 수를 줄여 요청 한도와 비용을 아끼기 위해서입니다.
- 입력: 보유 재료 목록 (소비기한 오름차순, null은 맨 뒤, **최대 20개**), 카테고리별 `need`, 재사용으로 고른 레시피 제목 (중복 방지)

```json
{
  "ingredients": [
    { "catalogId": 3, "name": "당근", "expireInDays": 1 },
    { "catalogId": 5, "name": "무지방 우유", "expireInDays": 2 },
    { "catalogId": 7, "name": "감자", "expireInDays": 5 },
    { "catalogId": 11, "name": "파스타면", "expireInDays": null }
  ],
  "need": { "KOREAN": 3, "WESTERN": 1, "DESSERT": 2 },
  "excludeTitles": ["당근 크림 파스타", "감자 우유 수프"]
}
```

- 호출 실패(타임아웃·5xx·`429` 사용 한도 초과) 또는 JSON 파싱 실패 → **1회 재시도** (`429`면 2초 뒤). 그래도 실패하면 재사용 레시피만 씁니다.
- 재료가 적어서 만들 수 있는 레시피가 `need`보다 적으면 적게 만들어도 됩니다 (재시도하지 않음).

**4-c) 응답 검증** — 레시피 단위로 검사하고, 규칙을 어긴 레시피는 **통째로 버립니다.**

| 필드 | 규칙 |
|---|---|
| `title` | 공백 아님, 1~100자 |
| `category` | `need`에 있는 카테고리 중 하나 |
| `cookTime` | 정수 1~180 |
| `ingredientCatalogIds` | 1개 이상, **중복은 제거**, 모든 ID가 LLM 입력 목록에 있어야 함 |
| `steps` | 1~10개, 각 항목 공백 아님, 500자 이하 |
| `imageKeyword` | 없거나 비어 있으면 5)의 대체 검색어 사용 (레시피는 버리지 않음) |
| `reason` | 없어도 됨. 200자 초과 시 자름 (DB 컬럼 VARCHAR(200)) |

- 카테고리별로 `need`보다 많이 오면 앞에서부터 `need`개만 씁니다.
- `ingredientCatalogIds` 중복을 제거하지 않으면 `UNIQUE (recipe_id, ingredient_catalog_id)` 제약에 걸립니다.

**5) 이미지 검색** — [부록 C](#부록-c-unsplash-연동)
- **새로 생성한 레시피만** 검색합니다. 재사용 레시피는 저장된 `image_url`을 그대로 씁니다.
- 순서: `imageKeyword`로 검색 → 결과 없으면 카테고리 대체 검색어로 검색 → 그래도 없거나 오류·한도 초과면 이미지 필드 전부 `null`
- 사진을 쓰기로 정하면 Unsplash **다운로드 기록 API**를 호출합니다 (부록 C).
- 이미지 때문에 API가 실패하지는 않습니다.

**6) 저장** (하나의 트랜잭션)

| 테이블 | 값 |
|---|---|
| `recipes` | 새 레시피만: `title`, `category`, `cook_time`, `image_url`, `image_author_name`, `image_author_url`, `source = 'AI'`, `description = null` |
| `recipe_ingredients` | 새 레시피만: 레시피 × catalogId (LLM이 준 순서대로 insert) |
| `recipe_steps` | 새 레시피만: `step_order` = 1부터, `description` |
| `recipe_suggestions` | 이번 묶음의 **모든 레시피**(재사용 + 새 레시피): `user_id`, `recipe_id`, `batch_id`(이번 묶음 UUID, 모두 같은 값), `inventory_hash`, `suggested_at`(모두 같은 시각), `reason`(새 레시피는 LLM 값, 재사용 레시피는 `null`) |

> LLM 호출과 Unsplash 호출은 **트랜잭션 밖**에서 합니다. 외부 호출 동안 DB 커넥션을 잡고 있지 않게 하기 위해서입니다.

**7) 필터 · 정렬** (캐시 경로와 생성 경로 모두 동일)
- 대상: 최근 묶음(`batch_id`)의 레시피
- `category`가 `ALL`이 아니면 해당 카테고리만 남깁니다. 결과가 0개면 `[]`입니다.
- **긴급도 점수**를 **지금 시점 재고 기준**으로 계산합니다 (캐시된 묶음이어도 매번 다시 계산 → 날짜가 지나면 순서가 바뀜).

```
레시피 점수 = Σ (레시피 재료별 가중치)

재료 가중치: 해당 catalogId의 사용자 active 재고 중 가장 빠른 소비기한(expiresOn)으로 D = 소비기한 − 오늘
  D ≤ 1      → 3   (이미 지난 것 포함)
  2 ≤ D ≤ 3  → 2
  4 ≤ D ≤ 7  → 1
  D ≥ 8, 소비기한 없음, 재고 없음 → 0
```

- 정렬: 점수 내림차순 → `cookTime` 오름차순 → `recipeId` 오름차순

---

## 3. API ② 레시피 상세

**화면:** `recipe/detail` — 이미지, 제목, 조리시간, 사용재료(원형 아이콘), 조리방법 Step, "다 만들었어요!" 버튼

```
GET /api/me/recipes/{recipeId}
```

### 3-1. Request

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| Path | `recipeId` | Long | Y | 레시피 ID |

### 3-2. Response `200 OK`

| 필드 | 타입 | Null | 설명 |
|---|---|---|---|
| `recipeId` | Long | ✕ | 레시피 ID |
| `title` | String | ✕ | 상단 제목 |
| `category` | RecipeCategory | ✕ | 카테고리 |
| `cookTime` | Integer | ✕ | 조리시간(분) |
| `imageUrl` | String | ○ | Unsplash 사진 URL. 못 찾으면 `null` (프론트는 회색 박스 표시) |
| `imageAuthorName` | String | ○ | 사진 작가 이름. `imageUrl`이 `null`이면 `null` |
| `imageAuthorUrl` | String | ○ | 작가 Unsplash 프로필 링크 (UTM 포함). `imageUrl`이 `null`이면 `null` |
| `ingredients` | Array | ✕ | 사용재료 |
| `ingredients[].ingredientCatalogId` | Long | ✕ | 재료 마스터 ID |
| `ingredients[].inventoryId` | Long | ○ | 차감에 쓸 사용자 재고 ID. 보유하지 않으면 `null` |
| `ingredients[].name` | String | ✕ | 재료 이름 |
| `steps` | Array | ✕ | 조리방법 |
| `steps[].step` | Integer | ✕ | 1부터 시작 |
| `steps[].description` | String | ✕ | 단계 설명 |

```json
{
  "success": true,
  "data": {
    "recipeId": 1,
    "title": "당근 크림 파스타",
    "category": "WESTERN",
    "cookTime": 20,
    "imageUrl": "https://images.unsplash.com/photo-1621996346565-e3dbc646d9a9?ixid=...&w=1080",
    "imageAuthorName": "Hong Gildong",
    "imageAuthorUrl": "https://unsplash.com/@hong?utm_source=pickdo&utm_medium=referral",
    "ingredients": [
      { "ingredientCatalogId": 3, "inventoryId": 21, "name": "당근" },
      { "ingredientCatalogId": 7, "inventoryId": 22, "name": "감자" },
      { "ingredientCatalogId": 5, "inventoryId": 34, "name": "무지방 우유" },
      { "ingredientCatalogId": 11, "inventoryId": null, "name": "파스타면" }
    ],
    "steps": [
      { "step": 1, "description": "끓는 물에 면을 넣어 삶아주세요!" },
      { "step": 2, "description": "면을 건져내고 다른 재료들과 볶아주세요" },
      { "step": 3, "description": "접시에 플레이팅하세요! 완성입니다." }
    ]
  },
  "error": null
}
```

- **출처 표시 (프론트, 필수):** `imageUrl`이 있으면 이미지 영역 오른쪽 아래에 작은 반투명 글씨로 `Photo by {imageAuthorName} on Unsplash`를 표시합니다. 작가 이름은 `imageAuthorUrl`, "Unsplash"는 `https://unsplash.com/?utm_source=pickdo&utm_medium=referral`로 연결합니다. 레이아웃은 바꾸지 않습니다.
- 예시의 파스타면 `inventoryId: null`은 추천을 받은 뒤 파스타면을 소진·폐기한 경우입니다. 추천되는 레시피는 새로 생성한 것이든 재사용한 것이든 보유 재료만 쓰므로, 추천 직후에는 모든 재료에 `inventoryId`가 있습니다.

### 3-3. Error

| HTTP | code | 상황 |
|---|---|---|
| 404 | `RECIPE_NOT_FOUND` | 레시피가 없음, 또는 이 사용자에게 추천된 적 없음 |

> 다른 사용자의 레시피도 403이 아니라 404로 응답합니다 (존재 여부 노출 방지).

### 3-4. 처리 로직

1. `recipe_suggestions`에 (`user_id = 나`, `recipe_id`) 행이 **날짜와 상관없이** 1개라도 있는지 확인합니다. 없으면 `404`입니다.
2. `recipes`, `recipe_ingredients`(id 오름차순), `recipe_steps`(`step_order` 오름차순)를 조회합니다.
3. **`inventoryId` 매핑:** 재료마다 같은 `ingredient_catalog_id`를 가진 사용자의 `active` 재고를 찾습니다.
    - 여러 건이면 소비기한이 가장 빠른 것 (소비기한 없음은 뒤) → 같으면 재고 ID가 작은 것
    - 없으면 `null`
4. LLM·Unsplash는 호출하지 않습니다.

---

## 4. 화면 ③ 요리에 추가할 재료 목록 (기존 API 재사용)

**화면:** `recipe/detail/add` — "요리에 추가할 재료를 선택하세요" 체크박스 목록 + "추가" 버튼

### 4-1. 백엔드

**새로 구현할 API는 없습니다.** 팀원이 만든 재료 목록 조회 API를 그대로 씁니다.

기존 API 응답에 아래 필드가 있어야 합니다. 없으면 기존 API 응답에 필드를 추가합니다.

| 필드 | 타입 | 용도 |
|---|---|---|
| 재고 ID (`inventoryId` 등) | Long | 완료 API의 `usages[].inventoryId` |
| 재료 마스터 ID (`ingredientCatalogId` 등) | Long | 상세 화면 재료와 중복 판단 |
| 재료 이름 | String | 체크박스 라벨 |
| 상태 필터 | — | `active` 재고만 조회할 수 있어야 함 |

### 4-2. 프론트 처리 (참고)

1. 상세 화면 `ingredients`에 이미 있는 `ingredientCatalogId`는 목록에서 뺍니다.
2. "추가"를 누르면 선택한 재료를 **프론트 상태로만** 상세 화면 사용재료 목록에 붙입니다. 서버에는 저장하지 않습니다.
3. 추가한 재료는 ④ 완료 API의 `usages`에 함께 보냅니다.

---

## 5. API ④ 요리 완료 · 재료 사용량 반영

**화면:** `recipe/detail/delete` — "맛있게 드셨나요? 재료 사용량 조사를 시작할게요!" 재료별 0~100% 슬라이더 + "완료" 버튼

```
POST /api/me/recipes/{recipeId}/complete
```

### 5-1. Request

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| Path | `recipeId` | Long | Y | 레시피 ID |

**Body** `application/json`

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `usages` | Array | Y | 1~50개 |
| `usages[].inventoryId` | Long | Y | 내 `active` 재고(`inventory_items.id`), 배열 안에서 중복 불가 |
| `usages[].usageRate` | Integer | Y | 0~100 (슬라이더 값) |

```json
{
  "usages": [
    { "inventoryId": 21, "usageRate": 40 },
    { "inventoryId": 22, "usageRate": 100 },
    { "inventoryId": 34, "usageRate": 0 },
    { "inventoryId": 57, "usageRate": 30 }
  ]
}
```

- 상세 화면에서 `inventoryId: null`인 재료는 슬라이더를 보여주지 않고 요청에서도 뺍니다.
- `inventoryId`는 **레시피 재료가 아니어도 됩니다.** `recipe/detail/add`에서 추가한 재료(위 예시의 57)가 포함되기 때문입니다.

### 5-2. Response `200 OK`

```json
{ "success": true, "data": null, "error": null }
```

### 5-3. Error (검사 순서대로)

| 순서 | HTTP | code | 상황 |
|---|---|---|---|
| 1 | 400 | `EMPTY_USAGES` | `usages`가 없거나 비어 있음, 또는 50개 초과 |
| 1 | 400 | `INVALID_USAGE_RATE` | `usageRate`가 null이거나 0~100 범위 밖 |
| 1 | 400 | `DUPLICATE_INVENTORY` | 같은 `inventoryId`가 두 번 이상 |
| 2 | 404 | `RECIPE_NOT_FOUND` | 레시피 없음 / 이 사용자에게 추천된 적 없음 |
| 3 | 404 | `INVENTORY_NOT_FOUND` | 재고 ID가 없음 |
| 3 | 403 | `INVENTORY_ACCESS_DENIED` | 다른 사용자의 재고 |
| 3 | 409 | `INVENTORY_NOT_ACTIVE` | 이미 소진·폐기된 재고 (`consumed`·`disposed`) |

- 1번은 Bean Validation(`@NotEmpty`, `@Size(max = 50)`, `@NotNull`, `@Min(0)`, `@Max(100)`)과 서비스 내 중복 검사로 처리합니다.
- 3번에서 하나라도 실패하면 **아무것도 차감하지 않습니다.**

### 5-4. 처리 로직 (하나의 트랜잭션)

1. 요청 검증 (5-3의 1번)
2. 레시피 접근 권한 확인 (3-4의 1번과 동일)
3. `inventoryId` 전부를 한 번에 조회하고 존재·소유자·상태를 확인합니다.
4. 재고마다 차감합니다.

`usageRate`는 **현재 남은 양의 몇 %를 썼는지**입니다. `remainingQuantity`(수량, `numeric(12,3)`)에 적용합니다.

```
usageRate = 0   → 변경 없음 (호출도 안 함)
그 외           → 사용량   = remainingQuantity × usageRate / 100   (scale 3, RoundingMode.HALF_UP)
                  새 남은 양 = remainingQuantity − 사용량
                  usageRate = 100 이면 사용량 = remainingQuantity, 새 남은 양 = 0
                  새 남은 양 ≤ 0 이면 소진(consumed) 처리
```

| 남은 양 (`remainingQuantity`) | 슬라이더 | 사용량 | 새 남은 양 |
|---|---|---|---|
| 200.000 (ml) | 50% | 100.000 | 100.000 |
| 3.000 (개) | 40% | 1.200 | 1.800 |
| 0.500 (kg) | 100% | 0.500 | 0 → `consumed` |

**기존 코드 호출 방식 (확정: B)**

기존 코드 확인 결과 `InventoryItem.updateStatus(quantity, statusId)`는 `remainingQuantity -= quantity` 후 **남은 양과 상관없이** 상태를 `statusId`로 바꿉니다. 그래서 부분 사용에는 쓸 수 없습니다.

| 경우 | 구현 |
|---|---|
| 부분 사용 (새 남은 양 > 0) | `InventoryItem`에 새 메서드 `decreaseRemainingQuantity(BigDecimal quantity)`를 **추가**해서 남은 양만 줄임. 상태는 `active` 유지, `ConsumptionEvent` 없음 |
| 전부 사용 (새 남은 양 ≤ 0) | 기존 `DisposeService.updateStatus`를 `statusId = consumed`, `quantity = 현재 remainingQuantity`로 호출 (기존 로직·`ConsumptionEvent` 그대로) |

`decreaseRemainingQuantity` 규칙:
- `quantity`가 0 이하이거나 `remainingQuantity`보다 크거나 같으면 `IllegalArgumentException` (그 경우는 전부 사용 경로로 가야 함)
- `remainingQuantity = remainingQuantity − quantity`만 하고 다른 필드는 건드리지 않음

- 기존 메서드(`updateStatus` 등)와 기존 서비스 코드는 **수정하지 않습니다.** `InventoryItem`에는 새 메서드만 추가합니다 (팀원에게 공유).
- `DisposeService.updateStatus`는 `@Transactional`(기본 전파 `REQUIRED`)이라 완료 API의 트랜잭션에 합류합니다. 하나라도 실패하면 전체가 롤백됩니다.
- 부분 사용은 `ConsumptionEvent`가 남지 않습니다 (부록 E, 리포트 기능 만들 때 구체화).

5. 하나라도 실패하면 전체를 롤백합니다.

> "완료" 버튼 연타로 중복 차감될 수 있으니, 프론트에서 요청 중에는 버튼을 비활성화합니다.

---

## 부록 A. DB 변경

### A-1. 기존 테이블 (변경 전)

```sql
recipes            (id, title, description, source, created_at)
recipe_ingredients (id, recipe_id, ingredient_catalog_id)   -- ingredient_catalog_id → ingredient_catalog.id, UNIQUE (recipe_id, ingredient_catalog_id), INDEX (recipe_id)
recipe_suggestions (id, user_id, recipe_id, suggested_at, reason)  -- INDEX (user_id, suggested_at)
```

### A-2. `recipes` 컬럼 추가 · `recipe_steps` 신규

```sql
ALTER TABLE recipes
  ADD COLUMN category  VARCHAR(20)  NOT NULL
    CHECK (category IN ('KOREAN', 'CHINESE', 'WESTERN', 'JAPANESE', 'DESSERT')),
  ADD COLUMN cook_time INT          NOT NULL
    CHECK (cook_time BETWEEN 1 AND 180),
  ADD COLUMN image_url         VARCHAR(1000),                 -- nullable (Unsplash URL은 쿼리스트링이 길어서 1000)
  ADD COLUMN image_author_name VARCHAR(255),                  -- nullable
  ADD COLUMN image_author_url  VARCHAR(500);                  -- nullable

ALTER TABLE recipes ALTER COLUMN description DROP NOT NULL;  -- 이미 nullable이면 생략

-- 재사용 레시피 검색용 (2-4의 4-a)
CREATE INDEX idx_recipes_source_category ON recipes (source, category, created_at DESC);

CREATE TABLE recipe_steps (
  id          BIGSERIAL PRIMARY KEY,
  recipe_id   BIGINT       NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
  step_order  INT          NOT NULL CHECK (step_order >= 1),
  description VARCHAR(500) NOT NULL,
  UNIQUE (recipe_id, step_order)
);
```

| 컬럼 | 화면 / 용도 | 비고 |
|---|---|---|
| `recipes.category` | 목록 카테고리 탭 | `ALL`은 저장하지 않음 |
| `recipes.cook_time` | 목록·상세 "조리시간 20분" | 분 단위 |
| `recipes.image_url` | 상세 상단 이미지 | Unsplash `urls.regular`. 검색 실패 시 `null` |
| `recipes.image_author_name` · `image_author_url` | 이미지 위 출처 표시 | Unsplash 약관상 필수. 이미지가 없으면 `null` |
| `recipe_steps` | 상세 Step 1~N | `UNIQUE`가 인덱스 역할 → 별도 인덱스 불필요 |

> `recipes`에 기존 데이터가 없어서 `NOT NULL` 컬럼을 기본값 없이 추가합니다 (0번 12번).

### A-2-1. 실제 적용 상태 (V5 적용 후 확인)

`V5__add_recipe_schema.sql`은 Unsplash 결정 전에 만들어져서 이미지 출처 컬럼이 빠졌습니다. **V5는 이미 DB에 적용됐으므로 수정하지 않고 V6으로 보완합니다.**

```sql
-- V6__add_recipe_image_author.sql
ALTER TABLE recipes
  ADD COLUMN image_author_name VARCHAR(255),
  ADD COLUMN image_author_url  VARCHAR(500);

ALTER TABLE recipes ALTER COLUMN image_url TYPE VARCHAR(1000);  -- Unsplash URL은 쿼리스트링이 길어서
```

| 항목 | V5 실제 | 처리 |
|---|---|---|
| `recipes.image_author_name`, `image_author_url` | 없음 | V6에서 추가 |
| `recipes.image_url` | VARCHAR(500) | V6에서 VARCHAR(1000)으로 변경 |
| `recipe_suggestions.reason` | VARCHAR(200) | 스키마 유지. **LLM `reason`은 200자에서 자름** (4-c의 255자 규칙 대신) |
| `recipe_suggestions.inventory_hash` | VARCHAR(64) | 그대로 사용 (CHAR(64)와 동일하게 64자 hex 저장) |

### A-3. `recipe_suggestions` 컬럼 추가 (추천 묶음 · 재료 구성 해시)

```sql
ALTER TABLE recipe_suggestions
  ADD COLUMN batch_id       UUID     NOT NULL,   -- 한 번의 추천 묶음. 같은 묶음은 같은 값
  ADD COLUMN inventory_hash CHAR(64) NOT NULL;   -- 추천 당시 재료 구성 SHA-256 (2-4의 2)

ALTER TABLE recipe_suggestions ALTER COLUMN reason DROP NOT NULL;  -- 재사용 레시피는 reason 없음. 이미 nullable이면 생략

CREATE INDEX idx_recipe_suggestions_batch ON recipe_suggestions (batch_id);
CREATE INDEX idx_recipe_suggestions_user_recipe ON recipe_suggestions (user_id, recipe_id);  -- 상세·완료 접근 확인용
```

- 캐시 확인(최근 묶음 조회)은 기존 인덱스 `(user_id, suggested_at)`를 씁니다.
- `recipe_suggestions`에 기존 데이터는 없습니다 (0번 12번).

### A-4. 재고 테이블

변경 없음. 남은 양은 기존 `inventory_items.remainingQuantity`(`numeric(12,3)`)를 사용합니다.

### A-5. 컬럼 사용 규칙

| 컬럼 | 값 |
|---|---|
| `recipes.source` | LLM 생성 레시피는 `'AI'` |
| `recipes.description` | 화면에 쓰이지 않음. `null` 저장 |
| `recipe_suggestions.reason` | 새로 생성한 레시피는 LLM이 준 추천 사유, 재사용 레시피는 `null`. 응답에는 넣지 않음 |

> AI 레시피는 **모든 사용자가 공유**합니다 (재사용). 사용자별 접근은 `recipe_suggestions`로 판단합니다.

---

## 부록 B. LLM 연동

### B-1. 설정

| 항목 | 값 |
|---|---|
| 서비스 | Google Cloud **Vertex AI**의 Gemini 모델 (Cloud 콘솔 → Agent Platform → 설정 → API 키에서 발급, 제한사항: Agent Platform API) |
| 결제 | Google Cloud 무료 체험 크레딧에서 차감 (크레딧 만료: 2026-12-30). 예산 알림 설정 필수 |
| 모델 | `gemini-2.5-flash` (호출 테스트 완료). 설정값 `gemini.model`로 분리 |
| 환경변수 | `GEMINI_API_KEY` (코드·Git에 넣지 않음) |
| 요청 | `POST https://aiplatform.googleapis.com/v1/publishers/google/models/{model}:generateContent` |
| 헤더 | `x-goog-api-key: {GEMINI_API_KEY}`, `Content-Type: application/json` |
| 호출 단위 | 추천 묶음 1개당 **최대 1회** (재시도 제외) |
| 출력 형식 | `generationConfig.responseMimeType = "application/json"` + `responseSchema`(B-2)로 JSON 강제 |
| 사고(thinking) | `generationConfig.thinkingConfig.thinkingBudget = 0` (끔) |
| `maxOutputTokens` | 8192 (1회에 레시피 최대 15개) |
| 타임아웃 | 60초 |
| 재시도 | 2-4의 4-b 규칙 (1회, `429`면 2초 뒤) |
| 클라이언트 | Spring `RestClient` (또는 기존 프로젝트의 HTTP 클라이언트) |

> **thinking을 끄는 이유:** 테스트 호출에서 답변 485토큰에 사고 토큰이 1,972토큰 추가로 쓰였습니다. 사고 토큰도 비용과 응답 시간에 포함되고 `maxOutputTokens` 한도도 잡아먹으므로, JSON 생성 작업에서는 끕니다. 결과 품질이 너무 낮으면 `thinkingBudget`을 작게(예: 512) 켜서 비교합니다.

**요청 본문 예시**

```json
{
  "systemInstruction": { "parts": [{ "text": "B-3의 System instruction" }] },
  "contents": [{ "role": "user", "parts": [{ "text": "B-3의 User 프롬프트" }] }],
  "generationConfig": {
    "responseMimeType": "application/json",
    "responseSchema": { "B-2의 responseSchema": "..." },
    "maxOutputTokens": 8192,
    "thinkingConfig": { "thinkingBudget": 0 }
  }
}
```

> Vertex AI에서는 `contents`에 `"role": "user"`를 꼭 넣습니다.

**LLM 교체 대비:** `LlmRecipeClient` 인터페이스를 두고 `GeminiRecipeClient`로 구현합니다. 나중에 다른 모델로 바꿀 때 구현체만 교체합니다.

**주의사항**
- 한도를 넘거나 일시 오류가 나면 `429`/`5xx`가 옵니다 → 4-b 규칙대로 처리합니다.
- 재료 이름·소비기한 외의 개인정보(이름, 이메일, 사용자 ID)는 프롬프트에 **넣지 않습니다.**
- 응답의 `finishReason`이 `STOP`이 아니면(예: `MAX_TOKENS`) 파싱 실패로 보고 재시도 규칙을 적용합니다.

### B-2. 응답 스키마

```json
{
  "recipes": [
    {
      "title": "당근 크림 파스타",
      "category": "WESTERN",
      "cookTime": 20,
      "ingredientCatalogIds": [3, 7, 5, 11],
      "steps": [
        "끓는 물에 면을 넣어 삶아주세요!",
        "면을 건져내고 다른 재료들과 볶아주세요",
        "접시에 플레이팅하세요! 완성입니다."
      ],
      "imageKeyword": "carrot cream pasta",
      "reason": "소비기한이 임박한 당근과 우유를 활용할 수 있어요."
    }
  ]
}
```

**`responseSchema`** (요청의 `generationConfig`에 넣음)

```json
{
  "type": "OBJECT",
  "properties": {
    "recipes": {
      "type": "ARRAY",
      "items": {
        "type": "OBJECT",
        "properties": {
          "title":                { "type": "STRING" },
          "category":             { "type": "STRING", "enum": ["KOREAN", "CHINESE", "WESTERN", "JAPANESE", "DESSERT"] },
          "cookTime":             { "type": "INTEGER" },
          "ingredientCatalogIds": { "type": "ARRAY", "items": { "type": "INTEGER" } },
          "steps":                { "type": "ARRAY", "items": { "type": "STRING" } },
          "imageKeyword":         { "type": "STRING" },
          "reason":               { "type": "STRING" }
        },
        "required": ["title", "category", "cookTime", "ingredientCatalogIds", "steps", "imageKeyword"]
      }
    }
  },
  "required": ["recipes"]
}
```

- 응답 텍스트는 `candidates[0].content.parts[0].text`에 JSON 문자열로 들어옵니다.
- 스키마를 강제해도 **4-c 검증은 반드시 합니다** (없는 catalogId, 개수 초과 등은 스키마로 막을 수 없음).
- 알 수 없는 필드는 무시합니다 (`FAIL_ON_UNKNOWN_PROPERTIES = false`).

### B-3. 프롬프트

**System instruction**

```
당신은 자취생을 위한 레시피 추천 도우미입니다.
반드시 지정된 JSON 형식으로만 답하세요.
```

**User** (`{...}`는 서버가 채움. 4-b의 입력 JSON을 그대로 넣음)

```
아래 입력을 보고 레시피를 만들어 주세요.

{4-b 입력 JSON}

- ingredients: 사용자가 가진 재료. expireInDays는 소비기한까지 남은 일수(null은 소비기한 없음)
- need: 카테고리별로 만들 레시피 개수
- excludeTitles: 이미 추천한 레시피. 같거나 비슷한 레시피는 만들지 마세요.

규칙:
1. need에 있는 카테고리만, 카테고리마다 최대 need개를 만드세요.
   가진 재료로 그럴듯한 레시피를 만들기 어려우면 더 적게 만들어도 되고, 0개여도 됩니다.
2. 재료는 ingredients의 catalogId만 사용하세요. 목록에 없는 재료는 쓰지 마세요.
   소금, 설탕, 식용유 같은 기본 양념은 ingredientCatalogIds에 넣지 말고 steps에서만 언급하세요.
3. expireInDays가 작은 재료를 우선 사용하세요.
4. cookTime은 1~180 사이 정수(분)입니다.
5. steps는 2~6단계로, 요리 초보도 따라 할 수 있게 한국어로 짧게 쓰세요.
6. imageKeyword는 음식 사진 검색용 영어 음식 이름입니다. (예: "carrot cream pasta")
7. reason은 이 레시피를 추천하는 이유를 한국어 한 문장으로 쓰세요.
```

---

## 부록 C. Unsplash 연동

| 항목 | 값 |
|---|---|
| 검색 요청 | `GET https://api.unsplash.com/search/photos?query={검색어}&per_page=1&orientation=landscape&content_filter=high` |
| 헤더 | `Authorization: Client-ID {UNSPLASH_ACCESS_KEY}`, `Accept-Version: v1` |
| 환경변수 | `UNSPLASH_ACCESS_KEY` (Secret Key는 사용하지 않음) |
| 타임아웃 | 3초 |
| 한도 | Demo: 시간당 50회 / Production(심사 후): 시간당 1,000회 |
| 호출 시점 | **새로 생성한 레시피만**, 생성 시 1회. 재사용 레시피·조회 API에서는 호출하지 않음 |

> 엔드포인트와 응답 필드는 구현 시 Unsplash 공식 문서로 한 번 더 확인합니다.

**응답에서 저장할 값** (`results[0]` 기준)

| 저장 컬럼 | 응답 필드 | 가공 |
|---|---|---|
| `image_url` | `urls.regular` | 그대로 저장 (**핫링크 필수** — 다운로드해서 다시 올리지 않음) |
| `image_author_name` | `user.name` | 그대로 |
| `image_author_url` | `user.links.html` | 뒤에 `?utm_source=pickdo&utm_medium=referral` 붙임 |
| (저장 안 함) | `links.download_location` | 아래 다운로드 기록 호출에 사용 |

**다운로드 기록 호출 (약관 필수)**
- 사진을 레시피 이미지로 쓰기로 정했을 때 **1회** 호출합니다.
- `GET {links.download_location}` + 같은 `Authorization` 헤더
- 실패해도 무시합니다 (레시피 저장은 계속 진행).

**검색 순서** (별도 기본 이미지 파일은 준비하지 않습니다)

```
1. imageKeyword로 검색 → 결과 있으면 사용 → 다운로드 기록 호출
2. 결과 없음 → 카테고리 대체 검색어로 검색 → 결과 있으면 사용 → 다운로드 기록 호출
3. 그래도 없음 / 타임아웃 / 오류 / 한도 초과(403·429) → 이미지 필드 전부 null
   (프론트는 와이어프레임의 회색 박스를 그대로 표시)
```

| 카테고리 | 대체 검색어 |
|---|---|
| `KOREAN` | `korean food` |
| `CHINESE` | `chinese food` |
| `WESTERN` | `western food` |
| `JAPANESE` | `japanese food` |
| `DESSERT` | `dessert` |

**한도 관리**
- 새 레시피 1개당 최대 3회 호출 (검색 2회 + 다운로드 기록 1회). Demo 한도(시간당 50회)에서는 새 레시피 15개 생성만으로 한도에 가까워질 수 있습니다.
- 응답 헤더 `X-Ratelimit-Remaining`이 0이면 그 요청 동안 남은 레시피는 검색하지 않고 이미지를 `null`로 둡니다.
- 한 번의 추천 요청 안에서 같은 검색어(특히 대체 검색어)는 결과를 재사용해 중복 호출하지 않습니다.

**Production 심사 대비**
- 출처 표시(3-2)와 다운로드 기록 호출이 실제 앱에서 동작해야 합니다.
- 앱 이름·로고가 Unsplash와 헷갈리지 않아야 합니다.

---

## 부록 D. 구현 구성 (참고)

기존 프로젝트의 패키지 구조·네이밍을 우선합니다.

| 구분 | 클래스 |
|---|---|
| Controller | `RecipeController` — API ① ② ④ |
| Service | `RecipeRecommendationService` (①), `RecipeQueryService` (②), `RecipeCompletionService` (④) |
| 보조 | `InventoryHashCalculator` (재료 구성 해시), `RecipeUrgencyScorer` (긴급도 점수), `LlmRecipeValidator` (4-c 검증) |
| 외부 연동 | `LlmRecipeClient` 인터페이스 + `GeminiRecipeClient` 구현, `ImageSearchClient` 인터페이스 + `UnsplashImageClient` 구현 (인터페이스로 분리해 테스트 시 mock, 나중에 교체 가능) |
| Entity | `Recipe`, `RecipeIngredient`, `RecipeStep`, `RecipeSuggestion` |
| Enum | `RecipeCategory` |
| DTO | `RecommendedRecipesResponse`, `RecipeDetailResponse`, `RecipeCompleteRequest`, LLM 응답용 `LlmRecipeResponse` |
| 설정 | `gemini.*`, `unsplash.*` |

**테스트할 것**

| 대상 | 케이스 |
|---|---|
| 재료 구성 해시 | 순서·중복이 달라도 같은 해시, 재료 추가·삭제 시 다른 해시, 남은 양만 바뀌면 같은 해시 |
| 캐시 | 최근 묶음 해시가 같으면 재사용 검색·LLM 미호출, 다르면 새 묶음 생성 |
| 재사용 | 보유 재료만으로 만들 수 있는 레시피만 선택, 재료 하나라도 없으면 제외, 카테고리당 3개 초과 안 함 |
| LLM 호출 | 모든 카테고리를 재사용으로 채우면 미호출, 모자란 카테고리만 `need`에 담아 1회 호출 |
| LLM 응답 검증 | 목록에 없는 catalogId, 중복 catalogId, `need`에 없는 category, cookTime 범위, 빈 steps, 카테고리별 `need` 초과 |
| LLM 실패 | 5xx·타임아웃·`429`·파싱 실패 시 재시도 1회, 그래도 실패하면 재사용 레시피만 반환, 재사용도 0개면 503, 새 레시피 저장 안 됨 |
| 긴급도 점수 | D ≤ 1 / 2~3 / 4~7 / 8 이상 / 소비기한 없음 / 재고 없음, 정렬 순서 |
| 재고 0개 | LLM 미호출, 빈 배열 |
| Unsplash | 1차 결과 없음 → 대체 검색어, 둘 다 실패 → 이미지 필드 전부 `null`, 한도 초과(403·429) → `null`, 사진 사용 시 다운로드 기록 호출, 작가 링크에 UTM 붙음, API는 성공 |
| 상세 | 추천 안 된 레시피 404, 재고 없는 재료 `inventoryId: null`, 이미지 `null` 허용 |
| 완료 | 0 / 100 / 중간값 / 계산 결과 0 → 소진, 중복 ID 400, 타인 재고 403, 소진된 재고 409, 실패 시 전체 롤백 |

---

## 부록 E. 결정 사항

| 항목 | 결정 |
|---|---|
| 레시피 개수 | 카테고리당 3개 (전체 탭 최대 15개) |
| 추천 갱신 시점 | 재료 구성이 바뀌었을 때만 새로 추천 (2-4의 2·3) |
| 레시피 재사용 | 다른 사용자가 만든 AI 레시피도 재사용, 모자란 만큼만 LLM 생성 |
| LLM | Vertex AI `gemini-2.5-flash`, API 키 방식, thinking 끔, 추천 1회당 호출 1회 (인터페이스로 분리해 나중에 교체 가능) |
| 이미지 | Unsplash (Pexels는 신규 키 발급 중단). 이미지 위 작은 글씨로 출처 표시. 대체 검색어 → 그래도 없으면 `null` |
| 사용량 슬라이더 기준 | 현재 남은 양의 % (5-4) |
| 남은 양 저장 방식 | 기존 `inventory_items.remainingQuantity`(수량) 사용, 컬럼 추가 없음 |

**나중에 구체화 (이번 구현 범위 아님, 리포트 기능 만들 때)**

| 항목 | 이번 구현에서의 동작 |
|---|---|
| 만들어 먹은 요리 기록 | 저장하지 않음 |
| 재료 부분 사용 기록 (언제 얼마나 썼는지) | 저장하지 않음. 1~99% 사용은 재고의 남은 양만 줄임 (100% 사용은 기존 소진 처리 로직 그대로) |

> 두 항목 모두 현재 UI에 없어서 이번 범위에서 제외합니다.