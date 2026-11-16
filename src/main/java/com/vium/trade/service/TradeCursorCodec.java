package com.vium.trade.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.trade.dto.TradeCursor;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class TradeCursorCodec {
	private final ObjectMapper objectMapper;

	public String encode(TradeCursor cursor) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(
			Map.of("requestedAt", cursor.requestedAt().toString(), "tradeId", cursor.tradeId())));
	}

	public TradeCursor decode(String raw) {
		try {
			var node = objectMapper.readTree(Base64.getUrlDecoder().decode(raw));
			var id = node.get("tradeId");
			var time = node.get("requestedAt");
			if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0
					|| time == null || !time.isString()) throw new IllegalArgumentException();
			return new TradeCursor(OffsetDateTime.parse(time.asString()), id.asLong());
		} catch (RuntimeException e) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor 값이 올바르지 않습니다");
		}
	}
}
