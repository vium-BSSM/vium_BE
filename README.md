자취생·1인 가구의 식재료 낭비를 줄이기 위한 서비스 PickDo(비움)의 백엔드 API.

## 재료 조회

`GET /api/me/ingredients?expiringSoon=false`

- 현재 사용자의 `active` 재료를 `data.ingredients` 배열로 반환한다. 결과가 없으면 빈 배열이다.
- 소비기한 오름차순, 동일 날짜는 재고 ID 오름차순이다. 소비기한이 없는 재료는 마지막에 표시한다.
- `expiringSoon` 생략 또는 `false`는 전체 보유 재료, `true`는 오늘부터 사용자 설정 `expiry_alert_days`(기본 2일) 이내에 소비기한이 도래하는 재료와 이미 기한이 지난 보유 재료를 반환한다. 소비기한이 없는 재료는 임박 조회에서 제외한다.
- 재료명은 카탈로그 이름을 사용하고, 카탈로그가 없는 재료는 직접 입력한 이름을 사용한다. 카테고리가 없으면 `categoryName`은 `null`이다.
- 인증 구현 전에는 `CurrentUserProvider`의 개발 사용자 ID를 사용한다. `dev` 프로필의 사용자 시드가 필요하다.
- 기존 ERD와 마이그레이션을 그대로 사용한다.

## 커밋 컨벤션

```
type: 설명
```

| type | 의미 |
|---|--|
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변화 없는 코드 구조 개선 |
| `docs` | 문서 수정 (README, CLAUDE.md 등) |
| `test` | 테스트 코드 추가/수정 |
| `chore` | 빌드/설정/의존성 등 기능과 무관한 변경 |
| `style` | 포맷팅 등 로직에 영향 없는 변경 |
| `rename` | 파일/폴더명 변경 |
| `remove` | 파일/코드 삭제 |
