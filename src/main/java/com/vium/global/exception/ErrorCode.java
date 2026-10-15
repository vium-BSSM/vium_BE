package com.vium.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	INVALID_IMAGE(HttpStatus.BAD_REQUEST, "이미지 또는 업로드 요청이 올바르지 않습니다."),
	IMAGE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "이미지는 최대 5MiB까지 업로드할 수 있습니다."),
	UNSUPPORTED_IMAGE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "JPEG 또는 PNG 이미지 파일만 업로드할 수 있습니다."),
	IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "이미지를 찾을 수 없습니다."),
	IMAGE_STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "이미지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해주세요."),
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청이 올바르지 않습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
	CONFLICT(HttpStatus.CONFLICT, "이미 존재하는 리소스입니다."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
	RECIPE_NOT_FOUND(HttpStatus.NOT_FOUND, "레시피를 찾을 수 없습니다."),
	RECIPE_GENERATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "레시피 생성에 실패했습니다."),
	NO_CATALOG_INGREDIENTS(HttpStatus.BAD_REQUEST, "카탈로그에서 재료를 선택해 등록해 주세요."),
	EMPTY_USAGES(HttpStatus.BAD_REQUEST, "사용 재료 목록이 비어있거나 50개를 초과합니다."),
	INVALID_USAGE_RATE(HttpStatus.BAD_REQUEST, "사용률은 0~100 범위여야 합니다."),
	DUPLICATE_INVENTORY(HttpStatus.BAD_REQUEST, "중복된 재고 ID가 있습니다."),
	INVENTORY_NOT_FOUND(HttpStatus.NOT_FOUND, "재고를 찾을 수 없습니다."),
	INVENTORY_ACCESS_DENIED(HttpStatus.FORBIDDEN, "이 재고에 접근할 수 없습니다."),
	INVENTORY_NOT_ACTIVE(HttpStatus.CONFLICT, "사용할 수 없는 상태의 재고입니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	ErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getDefaultMessage() {
		return defaultMessage;
	}
}
