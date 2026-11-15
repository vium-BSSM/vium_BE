ALTER TABLE inventory_items ADD COLUMN image_id BIGINT REFERENCES uploaded_images(id);

ALTER TABLE uploaded_images DROP CONSTRAINT uploaded_images_purpose_check;
ALTER TABLE uploaded_images ADD CONSTRAINT uploaded_images_purpose_check
    CHECK (purpose IN ('RECEIPT', 'PROFILE', 'RECIPE', 'INGREDIENT'));
