-- Test setup for dispose module
-- Units table seed data
INSERT INTO "units" ("id", "code", "name") VALUES (1, 'ml', '밀리리터');
INSERT INTO "units" ("id", "code", "name") VALUES (2, 'g', '그램');

-- Item statuses table seed data
INSERT INTO "item_statuses" ("id", "code", "name") VALUES (1, 'active', '보관중');
INSERT INTO "item_statuses" ("id", "code", "name") VALUES (2, 'consumed', '소비됨');
INSERT INTO "item_statuses" ("id", "code", "name") VALUES (3, 'disposed', '폐기됨');

-- Ingredient categories seed data
INSERT INTO "ingredient_categories" ("id", "name") VALUES (1, '유제품');

-- Event sources seed data
INSERT INTO "event_sources" ("id", "code", "name") VALUES (1, 'manual', '수동');
INSERT INTO "event_sources" ("id", "code", "name") VALUES (2, 'auto_estimated', '자동추정');
