package com.vium.global.exception;

import com.vium.global.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
		ErrorCode errorCode = e.getErrorCode();
		if (errorCode == ErrorCode.INTERNAL_ERROR) {
			log.error("서버 측 비즈니스 오류: code={}", errorCode.name(), e);
		} else if (errorCode.getStatus().is5xxServerError()) {
			log.warn("서버 측 비즈니스 오류: code={}", errorCode.name(), e);
		}
		return ResponseEntity.status(errorCode.getStatus())
			.body(ApiResponse.fail(new ApiResponse.ErrorObject(errorCode.name(), e.getMessage())));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
			.findFirst()
			.map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
			.orElse(ErrorCode.INVALID_REQUEST.getDefaultMessage());
		return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
			.body(ApiResponse.fail(new ApiResponse.ErrorObject(ErrorCode.INVALID_REQUEST.name(), message)));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		return ResponseEntity.badRequest().body(ApiResponse.fail(new ApiResponse.ErrorObject(
			ErrorCode.INVALID_REQUEST.name(), e.getName() + ": 값의 형식이 올바르지 않습니다")));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(HttpMessageNotReadableException e) {
		return ResponseEntity.badRequest().body(ApiResponse.fail(new ApiResponse.ErrorObject(
			ErrorCode.INVALID_REQUEST.name(), "요청 본문의 형식 또는 필수 필드를 확인해 주세요")));
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ApiResponse<Void>> handleUploadSize(MaxUploadSizeExceededException e) {
		return handleBusinessException(new BusinessException(ErrorCode.IMAGE_TOO_LARGE));
	}

	@ExceptionHandler(MultipartException.class)
	public ResponseEntity<ApiResponse<Void>> handleMultipart(MultipartException e, HttpServletRequest request) {
		return handleBusinessException(new BusinessException(request.getRequestURI().equals("/api/me/images")
			? ErrorCode.INVALID_IMAGE : ErrorCode.INVALID_REQUEST));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleUnsupportedMedia(HttpMediaTypeNotSupportedException e,
		HttpServletRequest request) {
		return ResponseEntity.status(415).body(ApiResponse.fail(new ApiResponse.ErrorObject(
			request.getRequestURI().equals("/api/me/images") ? "UNSUPPORTED_IMAGE_TYPE" : "INVALID_REQUEST",
			"지원하지 않는 Content-Type입니다.")));
	}

	@ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
	public ResponseEntity<ApiResponse<Void>> handleNotFound() {
		ErrorCode errorCode = ErrorCode.NOT_FOUND;
		return ResponseEntity.status(errorCode.getStatus()).body(failureBody(errorCode));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
		ErrorCode errorCode = ErrorCode.METHOD_NOT_ALLOWED;
		return ResponseEntity.status(errorCode.getStatus()).headers(e.getHeaders())
			.body(failureBody(errorCode));
	}

	private ApiResponse<Void> failureBody(ErrorCode errorCode) {
		return ApiResponse.fail(new ApiResponse.ErrorObject(errorCode.name(), errorCode.getDefaultMessage()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(Exception e, HttpServletRequest request) throws Exception {
		if (DisconnectedClientHelper.isClientDisconnectedException(e)) {
			// 응답을 다시 쓰지 않고 Spring 기본 resolver에 연결 종료 처리를 맡긴다.
			throw e;
		}
		log.error("처리되지 않은 예외: {} {}", request.getMethod(), request.getRequestURI(), e);
		ServerHttpObservationFilter.findObservationContext(request).ifPresent(context -> context.setError(e));
		ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
		return ResponseEntity.status(errorCode.getStatus()).body(failureBody(errorCode));
	}
}
