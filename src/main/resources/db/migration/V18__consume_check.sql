ALTER TABLE "inventory_items" ADD COLUMN "keep_confirmed_expires_on" date;

-- 재고당 소진 확인 알림을 최초 1회만 만들기 위한 중복 방지 인덱스.
CREATE UNIQUE INDEX ON "notifications" ("user_id", "inventory_item_id") WHERE "type" = 'consume_check';
