package com.vium.recipe.entity;

public enum RecipeCategory {
	ALL(null, true),
	KOREAN("한식", false),
	CHINESE("중식", false),
	WESTERN("양식", false),
	JAPANESE("일식", false),
	DESSERT("디저트", false);

	private final String displayName;
	private final boolean isFilterOnly;

	RecipeCategory(String displayName, boolean isFilterOnly) {
		this.displayName = displayName;
		this.isFilterOnly = isFilterOnly;
	}

	public String getDisplayName() {
		return displayName;
	}

	public boolean isFilterOnly() {
		return isFilterOnly;
	}
}
