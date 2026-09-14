package com.vium.global.auth;

public class AuthenticationUtil {

	private static final Long DEFAULT_USER_ID = 1L;

	public static Long getCurrentUserId() {
		return DEFAULT_USER_ID;
	}
}
