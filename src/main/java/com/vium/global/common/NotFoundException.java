package com.vium.global.common;

public class NotFoundException extends BusinessException {

	public NotFoundException(String message) {
		super(ErrorCode.NOT_FOUND, message);
	}
}
