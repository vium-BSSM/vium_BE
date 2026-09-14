package com.vium.global.common;

public class InvalidRequestException extends BusinessException {

	public InvalidRequestException(String message) {
		super(ErrorCode.INVALID_REQUEST, message);
	}
}
