package com.vium.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청이 올바르지 않습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
	CONFLICT(HttpStatus.CONFLICT, "이미 존재하는 리소스입니다."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
	RECIPE_NOT_FOUND(HttpStatus.NOT_FOUND, "레시피를 찾을 수 없습니다."),
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
