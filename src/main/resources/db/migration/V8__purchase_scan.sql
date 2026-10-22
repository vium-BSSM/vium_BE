ALTER TABLE purchases ADD COLUMN type varchar(20) NOT NULL DEFAULT 'RECEIPT'
  CHECK (type IN ('RECEIPT', 'ONLINE_ORDER'));
ALTER TABLE purchases ALTER COLUMN type DROP DEFAULT;

ALTER TABLE purchases ADD COLUMN status varchar(20) NOT NULL DEFAULT 'DRAFT'
  CHECK (status IN ('DRAFT', 'CONFIRMED'));
ALTER TABLE purchases ALTER COLUMN status DROP DEFAULT;

ALTER TABLE purchases ADD COLUMN image_id bigint REFERENCES uploaded_images(id);
ALTER TABLE purchases ADD COLUMN confirmed_at timestamp;

ALTER TABLE purchases DROP COLUMN image_url;
ALTER TABLE purchases DROP COLUMN ocr_status;

ALTER TABLE purchase_items ADD COLUMN storage_method_id smallint REFERENCES storage_methods(id);
ALTER TABLE purchase_items ADD COLUMN expires_on date;

CREATE INDEX idx_purchases_user_purchased_at ON purchases(user_id, purchased_at DESC);
