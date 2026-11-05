-- 레시피 상세(재료 분량 + 6~12단계)를 상세 조회 시점에 lazy 생성하기 위한 컬럼 추가
-- detail_status는 DEFAULT 'BASIC'을 유지한다: 기존 recipes insert가 전부 이 컬럼을 명시하지는 않는다.
ALTER TABLE recipes
  ADD COLUMN detail_status VARCHAR(20) NOT NULL DEFAULT 'BASIC'
    CHECK (detail_status IN ('BASIC', 'GENERATING', 'DETAILED')),
  ADD COLUMN detail_generation_started_at TIMESTAMPTZ;

ALTER TABLE recipe_ingredients
  ADD COLUMN amount VARCHAR(50);

-- 기본 양념(소금·설탕·간장 등)은 ingredient_catalog 재료가 아니므로 별도 테이블로 분리한다.
-- 보강 성공 시에만 행이 생기므로 amount는 NOT NULL로 둔다(보강 전 상태를 표현할 필요가 없음).
CREATE TABLE recipe_seasonings (
  id BIGSERIAL PRIMARY KEY,
  recipe_id BIGINT NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
  name VARCHAR(30) NOT NULL,
  amount VARCHAR(50) NOT NULL,
  sort_order INT NOT NULL CHECK (sort_order >= 1),
  UNIQUE (recipe_id, sort_order)
);
