package com.vium.image.controller;

import com.vium.global.common.ApiResponse;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.global.security.CurrentUserProvider;
import com.vium.image.dto.ImageUploadResponse;
import com.vium.image.dto.ImageAccessResponse;
import com.vium.image.entity.ImagePurpose;
import com.vium.image.service.ImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/api/me/images")
@RequiredArgsConstructor
public class ImageController {
	private final ImageService images;
	private final CurrentUserProvider currentUser;

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<ApiResponse<ImageUploadResponse>> upload(MultipartHttpServletRequest request) {
		var files = request.getFiles("file");
		var purposes = request.getParameterValues("purpose");
		if (files.size() != 1 || request.getMultiFileMap().size() != 1 || purposes == null || purposes.length != 1)
			throw new BusinessException(ErrorCode.INVALID_IMAGE);
		ImagePurpose purpose;
		try { purpose = ImagePurpose.valueOf(purposes[0]); }
		catch (IllegalArgumentException e) { throw new BusinessException(ErrorCode.INVALID_IMAGE); }
		return ResponseEntity.status(201).body(ApiResponse.ok(images.upload(currentUser.getCurrentUserId(), purpose, files.getFirst())));
	}

	@GetMapping("/{imageId}/access-url")
	public ResponseEntity<ApiResponse<ImageAccessResponse>> access(@PathVariable Long imageId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(ApiResponse.ok(images.access(currentUser.getCurrentUserId(), imageId)));
	}
}
