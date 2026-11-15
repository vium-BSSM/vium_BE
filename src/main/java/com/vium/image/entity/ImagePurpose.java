package com.vium.image.entity;

public enum ImagePurpose {
	RECEIPT, PROFILE, RECIPE, INGREDIENT;

	public boolean isPrivate() { return this == RECEIPT; }
}
