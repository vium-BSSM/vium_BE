package com.vium.recipe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("recipe.image-backfill")
public record RecipeImageBackfillProperties(String cron, String timeZone, int batchSize) {
}
