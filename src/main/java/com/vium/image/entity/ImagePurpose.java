package com.vium.image.entity;

public enum ImagePurpose {
	RECEIPT, PROFILE, RECIPE;

	public boolean isPrivate() { return this == RECEIPT; }
}
