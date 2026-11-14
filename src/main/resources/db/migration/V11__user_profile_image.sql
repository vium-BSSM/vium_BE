ALTER TABLE users ADD COLUMN profile_image_id BIGINT REFERENCES uploaded_images(id);
