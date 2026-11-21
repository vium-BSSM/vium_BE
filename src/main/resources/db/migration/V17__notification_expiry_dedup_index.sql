-- 재고당 소비기한 임박 알림(type='expiry')을 최초 1회만 만들기 위한 중복 방지 인덱스.
-- type 조건의 partial index로 둔다: 인덱스가 작고, 다른 알림 타입이 같은 (user_id, inventory_item_id)로
-- 여러 행을 가져야 하더라도 이 제약에 걸리지 않는다. 대신 전체 인덱스보다 쿼리 플래너가
-- type='expiry' 조건이 없는 질의에는 이 인덱스를 쓰지 못한다.
-- inventory_item_id가 NULL인 행은 이번 기능 대상이 아니라 영향이 없다.
CREATE UNIQUE INDEX ON "notifications" ("user_id", "inventory_item_id") WHERE "type" = 'expiry';
