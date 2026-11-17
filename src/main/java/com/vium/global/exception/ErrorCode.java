package com.vium.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	INVALID_IMAGE(HttpStatus.BAD_REQUEST, "이미지 또는 업로드 요청이 올바르지 않습니다."),
	IMAGE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "이미지는 최대 5MiB까지 업로드할 수 있습니다."),
	UNSUPPORTED_IMAGE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "JPEG 또는 PNG 이미지 파일만 업로드할 수 있습니다."),
	IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "이미지를 찾을 수 없습니다."),
	IMAGE_STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "이미지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해주세요."),
	SCAN_UNRECOGNIZABLE(HttpStatus.UNPROCESSABLE_ENTITY, "구매한 품목을 찾지 못했습니다. 다시 촬영하거나 직접 등록해 주세요."),
	PURCHASE_NOT_FOUND(HttpStatus.NOT_FOUND, "구매 내역을 찾을 수 없습니다."),
	PURCHASE_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "이미 재고에 등록된 구매 내역입니다."),
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청이 올바르지 않습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
	CONFLICT(HttpStatus.CONFLICT, "이미 존재하는 리소스입니다."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
	RECIPE_NOT_FOUND(HttpStatus.NOT_FOUND, "레시피를 찾을 수 없습니다."),
	RECIPE_GENERATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "레시피 생성에 실패했습니다."),
	NO_CATALOG_INGREDIENTS(HttpStatus.BAD_REQUEST, "카탈로그에서 재료를 선택해 등록해 주세요."),
	EMPTY_USAGES(HttpStatus.BAD_REQUEST, "사용 재료 목록이 비어있거나 50개를 초과합니다."),
	INVALID_USAGE_RATE(HttpStatus.BAD_REQUEST, "사용률은 0~100 범위여야 합니다."),
	DUPLICATE_INVENTORY(HttpStatus.BAD_REQUEST, "중복된 재고 ID가 있습니다."),
	INVENTORY_NOT_FOUND(HttpStatus.NOT_FOUND, "재고를 찾을 수 없습니다."),
	INVENTORY_ACCESS_DENIED(HttpStatus.FORBIDDEN, "이 재고에 접근할 수 없습니다."),
	INVENTORY_NOT_ACTIVE(HttpStatus.CONFLICT, "사용할 수 없는 상태의 재고입니다."),
	POST_NOT_FOUND(HttpStatus.NOT_FOUND, "게시물을 찾을 수 없습니다."),
	POST_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "소비기한이 1일 이상 남은 재료만 게시할 수 있습니다."),
	PRICE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "판매가는 추천가의 ±20% 안에서만 정할 수 있습니다."),
	POST_LOCKED(HttpStatus.CONFLICT, "거래가 진행 중인 게시물은 수정할 수 없습니다."),
	POST_NOT_OPEN(HttpStatus.CONFLICT, "거래 가능한 게시물이 아닙니다."),
	POST_EXPIRED(HttpStatus.CONFLICT, "소비기한이 지나 거래할 수 없는 게시물입니다."),
	TRADE_NOT_FOUND(HttpStatus.NOT_FOUND, "거래를 찾을 수 없습니다."),
	TRADE_FORBIDDEN(HttpStatus.FORBIDDEN, "이 거래를 처리할 권한이 없습니다."),
	TRADE_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "이미 진행 중인 거래가 있는 게시물입니다."),
	INVALID_TRADE_STATUS(HttpStatus.CONFLICT, "현재 거래 상태에서는 처리할 수 없습니다."),
	CANNOT_TRADE_OWN_POST(HttpStatus.BAD_REQUEST, "내 게시물에는 거래를 요청할 수 없습니다."),
	USER_BLOCKED(HttpStatus.FORBIDDEN, "차단했거나 차단된 사용자와는 거래·채팅할 수 없습니다."),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
	INSUFFICIENT_CREDIT(HttpStatus.CONFLICT, "크레딧이 부족합니다."),
	DAILY_REWARD_ALREADY_CLAIMED(HttpStatus.CONFLICT, "오늘은 이미 출석 보상을 받았습니다."),
	REVIEW_ALREADY_WRITTEN(HttpStatus.CONFLICT, "이미 후기를 남긴 거래입니다."),
	CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
	CANNOT_CHAT_OWN_POST(HttpStatus.BAD_REQUEST, "내 게시물에는 채팅을 시작할 수 없습니다."),
	ALREADY_REPORTED(HttpStatus.CONFLICT, "이미 신고한 대상입니다."),
	NOT_ENOUGH_DATA(HttpStatus.UNPROCESSABLE_ENTITY, "이 달에는 분석할 소진·폐기 기록이 없습니다."),
	AI_REPORT_GENERATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "리포트를 만들지 못했습니다. 잠시 후 다시 시도해 주세요."),
	INVENTORY_LOCKED(HttpStatus.CONFLICT, "게시물에 올라간 재고는 사용할 수 없습니다."),
	INVENTORY_HAS_HISTORY(HttpStatus.CONFLICT, "소진·폐기·거래 이력이 있는 재료는 삭제할 수 없습니다.");

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
