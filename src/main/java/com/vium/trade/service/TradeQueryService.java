package com.vium.trade.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.trade.dto.TradeCursor;
import com.vium.trade.dto.TradeListResponse;
import com.vium.trade.dto.TradeRole;
import com.vium.trade.entity.TradeStatus;
import com.vium.trade.repository.TradeQueryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TradeQueryService {
	private final TradeQueryRepository queryRepository;
	private final TradeCursorCodec cursorCodec;

	public TradeListResponse list(Long userId, TradeRole role, List<TradeStatus> statuses, String cursor, int size) {
		validateSize(size);
		var rows = queryRepository.findTrades(userId, role, statuses,
			cursor == null ? null : cursorCodec.decode(cursor), size + 1);
		boolean hasNext = rows.size() > size;
		var page = hasNext ? rows.subList(0, size) : rows;
		String nextCursor = null;
		if (hasNext) {
			var last = page.getLast();
			nextCursor = cursorCodec.encode(new TradeCursor(last.requestedAt(), last.tradeId()));
		}
		return new TradeListResponse(page, nextCursor, hasNext);
	}

	static void validateSize(int size) {
		if (size < 1 || size > 50) throw new BusinessException(ErrorCode.INVALID_REQUEST, "size는 1~50 사이여야 합니다");
	}
}
