package com.vium.recipe.entity;

import com.vium.global.exception.InvalidRequestException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "recipes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recipe {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "title", nullable = false, length = 200)
	private String title;

	@Column(name = "description", length = 1000)
	private String description;

	@Column(name = "category", nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	private RecipeCategory category;

	@Column(name = "cook_time", nullable = false)
	private Integer cookTime;

	@Column(name = "image_url", length = 1000)
	private String imageUrl;

	@Column(name = "image_author_name", length = 255)
	private String imageAuthorName;

	@Column(name = "image_author_url", length = 500)
	private String imageAuthorUrl;

	@Column(name = "source", length = 120)
	private String source;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Builder
	public Recipe(String title, String description, RecipeCategory category, Integer cookTime,
			String imageUrl, String source) {
		if (category != null && category.isFilterOnly()) {
			throw new InvalidRequestException("카테고리 'ALL'은 레시피에 저장할 수 없습니다");
		}
		this.title = title;
		this.description = description;
		this.category = category;
		this.cookTime = cookTime;
		this.imageUrl = imageUrl;
		this.source = source;
		this.createdAt = LocalDateTime.now();
	}
}
