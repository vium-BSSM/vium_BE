package com.vium.global.common;

public record ApiResponse<T>(boolean success, T data, ErrorObject error) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, data, null);
	}

	public static ApiResponse<Void> ok() {
		return new ApiResponse<>(true, null, null);
	}

	public static ApiResponse<Void> fail(ErrorObject error) {
		return new ApiResponse<>(false, null, error);
	}

	public record ErrorObject(String code, String message) {
	}
}
