package com.vium.recipe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "recipe_suggestions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipeSuggestion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "recipe_id", nullable = false)
	private Long recipeId;

	@Column(name = "batch_id", nullable = false)
	private UUID batchId;

	@Column(name = "inventory_hash", nullable = false, length = 64)
	private String inventoryHash;

	@Column(name = "suggested_at", nullable = false)
	private LocalDateTime suggestedAt;

	@Column(name = "reason", length = 200)
	private String reason;

	@Builder
	public RecipeSuggestion(Long userId, Long recipeId, UUID batchId, String inventoryHash,
			LocalDateTime suggestedAt, String reason) {
		this.userId = userId;
		this.recipeId = recipeId;
		this.batchId = batchId;
		this.inventoryHash = inventoryHash;
		this.suggestedAt = suggestedAt;
		this.reason = reason;
	}
}
