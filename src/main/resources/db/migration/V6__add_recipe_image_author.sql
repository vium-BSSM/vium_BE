-- Add image author columns to recipes table
ALTER TABLE recipes
  ADD COLUMN image_author_name VARCHAR(255),
  ADD COLUMN image_author_url VARCHAR(500);

-- Expand image_url for Unsplash URLs with query strings
ALTER TABLE recipes ALTER COLUMN image_url TYPE VARCHAR(1000);
