package com.vium.global.exception;

public class NotFoundException extends BusinessException {

	public NotFoundException(String message) {
		super(ErrorCode.NOT_FOUND, message);
	}
}
