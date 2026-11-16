package com.vium.post.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.post.dto.PostCursor;
import com.vium.post.dto.PostSort;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 목록 커서를 Base64(URL-safe) JSON으로 인코딩·디코딩한다.
 * DISTANCE 정렬: {"distanceMeters": 123, "postId": 456}
 * LATEST 정렬: {"createdAt": "2026-01-01T00:00:00+09:00", "postId": 456}
 * 잘못된 값(깨진 base64, JSON 형식 불일치, 정렬 모드와 맞지 않는 필드)은 전부 INVALID_REQUEST로 변환한다.
 */
@Component
@RequiredArgsConstructor
public class PostCursorCodec {

	private final ObjectMapper objectMapper;

	public String encode(PostSort sort, PostCursor cursor) {
		Map<String, Object> data = new LinkedHashMap<>();
		if (sort == PostSort.DISTANCE) {
			data.put("distanceMeters", cursor.distanceMeters());
		} else {
			data.put("createdAt", cursor.createdAt().toString());
		}
		data.put("postId", cursor.postId());
		byte[] json = objectMapper.writeValueAsBytes(data);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
	}

	public PostCursor decode(String cursor, PostSort sort) {
		try {
			byte[] json = Base64.getUrlDecoder().decode(cursor.getBytes(StandardCharsets.UTF_8));
			JsonNode node = objectMapper.readTree(json);
			long postId = node.get("postId").asLong();
			if (sort == PostSort.DISTANCE) {
				int distanceMeters = node.get("distanceMeters").asInt();
				return new PostCursor(distanceMeters, null, postId);
			}
			OffsetDateTime createdAt = OffsetDateTime.parse(node.get("createdAt").asString());
			return new PostCursor(null, createdAt, postId);
		} catch (RuntimeException e) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor 값이 올바르지 않습니다");
		}
	}
}
