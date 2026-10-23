# 재료 카탈로그·소비기한 시드

411개 재료와 612개 규칙을 오프라인으로 가공한다. 앱 실행 중에는 외부 데이터 API를 호출하지 않는다.

## 입력과 출처

- `ingredients.csv`: 대표 재료명, 기존 카테고리·단위, 원본 매핑, 선호 보관방법. `preferred_storage`는 생성 시 검증용이며 DB 컬럼이 아니다.
- `data/raw/foodkeeper.json`: [USDA FoodKeeper](https://catalog.data.gov/dataset/fsis-foodkeeper-data), CC0. 사용자 제공 원본은 v128, 원본 Version 날짜 `09-06-2018`, Product 661건이다.
- `i1250_stats.json`: [식품안전나라 Open API](https://www.foodsafetykorea.go.kr/apiMain.do)의 I1250 품목제조보고를 식품유형별로 집계한 12개 유형 통계. 수집일은 각 유형의 `fetched_at`을 따른다. USDA의 CC0를 이 데이터에 적용하지 않는다. 이용·재배포 조건은 제공기관의 해당 API 이용조건을 확인한다.
- `data/raw/i1250/`: 수집 원본 로컬 캐시. 용량 때문에 Git에서 제외한다. 원본부터 통계를 재검증하려면 수집 당시 파일을 별도로 확보해야 한다.
- `manual_rules.csv`: 직접 정한 서비스 추정값 및 보관방법 제외 규칙. `source=manual`이며 공공 실측값이 아니다. 각 행의 `reason`에 적용 상태와 선택 근거를 기록한다.
- `match_samples.csv`: 기존 OCR 픽스처와 합성 사례 150개. 실제 영수증 150건의 정확도 지표가 아니다. 별칭·한 글자 토큰·포함 검색 한계로 남은 불일치 15건은 후속 매칭 개선 대상이다.

## 재생성

저장소 루트에서 Python 3 표준 라이브러리만 사용한다.

```bash
# 원본이 없거나 의도적으로 갱신할 때만 실행한다.
python3 scripts/catalog/fetch_i1250.py
# 기존 로컬 원본을 통계로 고정한다. 네트워크 접근 없음.
python3 scripts/catalog/summarize_i1250.py
# 검토용 SQL과 리포트
python3 scripts/catalog/build_seed.py --output /tmp/vium-catalog-draft.sql
# 검토 완료 후 V9 생성 (아직 적용되지 않은 V9에 한함)
python3 scripts/catalog/build_seed.py
python3 scripts/catalog/build_seed.py --check
python3 -m unittest discover -s scripts/catalog -p 'test_*.py'
```

수집 키는 환경변수 `FOODSAFETY_API_KEY` 또는 로컬 `.env`에 둔다. 원본이 이미 있으면 재수집할 필요가 없다. CI와 시드 생성기는 I1250 원본이나 인증키 없이 통계 파일만 읽는다.

## 산출 규칙과 한계

FoodKeeper는 구매일 기준 `DOP_*`를 우선한다. DOP 지시가 있으면 포장 표시일·숙성 상태 같은 비수치 값도 존중하고 임의 일수로 변환하지 않는다. DOP가 없는 경우만 일반 묶음을 보며 조리·개봉·해동 조건은 제외한다. Min 우선, 없으면 Max를 사용한다. 주는 7일, 월은 30일, 년은 365일, 시간은 일 단위 내림(최소 1일)이다. 원본 실수 ID는 정수로 정규화한다.

I1250은 2015년 이후 신고 행에서 조건 없는 기간만 파싱한다. 통계 히스토그램을 펼친 뒤 양 끝 5%를 제거하고 nearest-rank p25를 사용한다. 잔여 표본 20개 미만이면 규칙을 만들지 않는다. p75/p25가 4 이상이면 분포 경고를 낸다. 보관 상태가 다른 제품이 섞일 수 있어 경고 자체는 생성 실패로 처리하지 않는다.

병합 순서는 manual > I1250 > FoodKeeper다. manual `-`는 해당 보관방법 규칙을 제거한다. 스캔의 cold → room → frozen 선택 결과가 `preferred_storage`와 같아야 한다. 규칙 누락이나 불일치가 있으면 기본 V9 파일은 생성·덮어쓰지 않고 종료 코드 1을 반환한다. `--output` 초안은 작성하지만 종료 코드는 여전히 1이다. `--check`는 파일을 변경하지 않는다.

떡국떡·떡볶이떡은 진공포장 기준 실온 90일/냉동 180일, 가래떡은 냉장 2일/냉동 90일이다. 식빵·통밀식빵은 실온 5일/냉동 90일, 어묵은 냉장 14일로 덮어쓴다. 묵류·절임식품은 검토한 p25를 유지한다. 분포 경고는 덮어쓰기 이후에도 출처 특성을 알리기 위해 표시한다.

두유·아몬드음료·일부 주스·우동면은 냉장 판매 상태를 가정한다. 상온 멸균 제품이나 개봉 상태를 이름만으로 구분하지 못한다. 값은 구매일 기준 서비스 추정치이며 제품 표시 보관조건·소비기한을 우선한다. 제조일 기준 I1250 기간과 구매일 사이 경과도 자동으로 보정하지 않는다.

## 마이그레이션 관리

`V9__seed_ingredient_catalog.sql`은 생성 파일이다. 수동 편집하지 않는다. 카테고리·단위·보관방법은 이름/코드로 조회하며 기존 동일 이름·유효시작일 충돌은 건너뛴다. 기본 `effective_from`은 `2000-01-01`이다.

**V9가 어느 환경에든 적용된 뒤에는 V9를 재생성해 변경하지 않는다.** 이후 변경은 새 마이그레이션(V10 이후)으로 기존 규칙의 `effective_to`와 새 규칙의 `effective_from`을 관리한다. 그 시점에는 생성기 출력 대상과 CI 비교 기준도 함께 갱신한다.
