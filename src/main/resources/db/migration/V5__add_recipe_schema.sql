-- Add columns to recipes table
ALTER TABLE recipes
  ADD COLUMN category VARCHAR(20) NOT NULL
    CHECK (category IN ('KOREAN', 'CHINESE', 'WESTERN', 'JAPANESE', 'DESSERT')),
  ADD COLUMN cook_time INT NOT NULL
    CHECK (cook_time BETWEEN 1 AND 180),
  ADD COLUMN image_url VARCHAR(500);

ALTER TABLE recipes ALTER COLUMN description DROP NOT NULL;

-- Create index for recipe reuse search (2-4의 4-a)
CREATE INDEX idx_recipes_source_category ON recipes (source, category, created_at DESC);

-- Create recipe_steps table
CREATE TABLE recipe_steps (
  id BIGSERIAL PRIMARY KEY,
  recipe_id BIGINT NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
  step_order INT NOT NULL CHECK (step_order >= 1),
  description VARCHAR(500) NOT NULL,
  UNIQUE (recipe_id, step_order)
);

-- Add columns to recipe_suggestions table
ALTER TABLE recipe_suggestions
  ADD COLUMN batch_id UUID NOT NULL,
  ADD COLUMN inventory_hash VARCHAR(64) NOT NULL;

ALTER TABLE recipe_suggestions ALTER COLUMN reason DROP NOT NULL;

-- Create indexes for recipe_suggestions
CREATE INDEX idx_recipe_suggestions_batch ON recipe_suggestions (batch_id);
CREATE INDEX idx_recipe_suggestions_user_recipe ON recipe_suggestions (user_id, recipe_id);
