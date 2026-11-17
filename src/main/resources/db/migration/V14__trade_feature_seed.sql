-- V14: 거래 기능 코드 테이블 시드 + uploaded_images.purpose 제약 확장.
-- credit_base_values 시드값은 docs/vium.dbml에 실제 수치가 없어 포함하지 않는다.

INSERT INTO "item_statuses" ("code", "name") VALUES
  ('shared', '나눔·판매');

ALTER TABLE uploaded_images DROP CONSTRAINT uploaded_images_purpose_check;
ALTER TABLE uploaded_images ADD CONSTRAINT uploaded_images_purpose_check
    CHECK (purpose IN ('RECEIPT', 'PROFILE', 'RECIPE', 'INGREDIENT', 'POST', 'CHAT'));
