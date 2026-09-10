INSERT INTO "auth_providers" ("code", "name") VALUES
  ('kakao', '카카오'),
  ('google', '구글'),
  ('apple', '애플');

INSERT INTO "units" ("code", "name") VALUES
  ('ea', '개'),
  ('g', '그램'),
  ('kg', '킬로그램'),
  ('ml', '밀리리터'),
  ('l', '리터');

INSERT INTO "ingredient_categories" ("name") VALUES
  ('유제품'),
  ('채소'),
  ('과일'),
  ('육류'),
  ('수산물'),
  ('곡류'),
  ('양념'),
  ('음료'),
  ('냉동식품'),
  ('기타');

INSERT INTO "storage_methods" ("code", "name") VALUES
  ('cold', '냉장'),
  ('frozen', '냉동'),
  ('room', '실온');

INSERT INTO "purchase_sources" ("code", "name") VALUES
  ('emart', '이마트'),
  ('coupang', '쿠팡'),
  ('market', '전통시장'),
  ('convenience', '편의점'),
  ('online', '온라인쇼핑'),
  ('etc', '기타');

INSERT INTO "item_statuses" ("code", "name") VALUES
  ('active', '보유중'),
  ('consumed', '소진'),
  ('disposed', '폐기');

INSERT INTO "event_sources" ("code", "name") VALUES
  ('manual', '수동'),
  ('auto_estimated', '자동추정');
