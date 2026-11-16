package com.vium.trade.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.post.entity.Post;
import com.vium.post.service.PostStateService;
import com.vium.trade.dto.TradeAccess;
import com.vium.trade.entity.Trade;
import com.vium.trade.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TradeLockService {
	private final TradeRepository tradeRepository;
	private final PostStateService postStateService;

	@Transactional(propagation = Propagation.MANDATORY)
	public LockedTrade lockParticipant(Long tradeId, Long userId) {
		TradeAccess access = access(tradeId);
		if (!access.isParticipant(userId)) throw new BusinessException(ErrorCode.TRADE_NOT_FOUND);
		return lock(tradeId, access);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public LockedTrade lockSystem(Long tradeId) {
		return lock(tradeId, access(tradeId));
	}

	private TradeAccess access(Long tradeId) {
		return tradeRepository.findAccessById(tradeId)
			.orElseThrow(() -> new BusinessException(ErrorCode.TRADE_NOT_FOUND));
	}

	private LockedTrade lock(Long tradeId, TradeAccess access) {
		Post post = postStateService.lockForUpdate(access.postId());
		Trade trade = tradeRepository.findByIdForUpdate(tradeId)
			.orElseThrow(() -> new BusinessException(ErrorCode.TRADE_NOT_FOUND));
		return new LockedTrade(trade, post);
	}

	public record LockedTrade(Trade trade, Post post) {}
}
