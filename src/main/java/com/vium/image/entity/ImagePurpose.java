package com.vium.image.entity;

public enum ImagePurpose {
	RECEIPT, PROFILE, RECIPE, INGREDIENT, POST, CHAT;

	public boolean isPrivate() { return this == RECEIPT || this == CHAT; }
}
