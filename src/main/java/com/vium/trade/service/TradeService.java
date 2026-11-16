package com.vium.trade.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.trade.dto.TradeResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 트랜잭션 커밋 뒤에 만료 결과를 예외로 바꾼다. 이 클래스에 @Transactional을 붙이지 않는다. */
@Service
@RequiredArgsConstructor
public class TradeService {
	private final TradeCommandService commandService;

	public TradeResult request(Long buyerId, Long postId) {
		return switch (commandService.request(buyerId, postId)) {
			case TradeOutcome.Done done -> done.result();
			case TradeOutcome.PostExpired ignored -> throw new BusinessException(ErrorCode.POST_EXPIRED);
		};
	}
}
