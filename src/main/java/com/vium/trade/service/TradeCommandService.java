package com.vium.trade.service;

import com.vium.credit.service.CreditService;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.inventory.service.InventoryService;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.service.PostExpiryProcessor;
import com.vium.post.service.PostStateService;
import com.vium.safety.service.UserBlockService;
import com.vium.trade.dto.TradeResult;
import com.vium.trade.dto.TradeCompleteResult;
import com.vium.trade.entity.Trade;
import com.vium.trade.entity.TradeStatus;
import com.vium.trade.repository.TradeRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TradeCommandService {
	private final PostStateService postStateService;
	private final PostExpiryProcessor postExpiryProcessor;
	private final TradeRepository tradeRepository;
	private final InventoryService inventoryService;
	private final CreditService creditService;
	private final UserBlockService userBlockService;
	private final List<TradeChatNotifier> notifiers;
	private final Clock clock;
	private final TradeLockService tradeLockService;
	private final TradeCompletionService completionService;

	@Transactional
	public TradeOutcome request(Long buyerId, Long postId) {
		Post post = postStateService.lockForUpdate(postId);
		if (post.isOwnedBy(buyerId)) {
			throw new BusinessException(ErrorCode.CANNOT_TRADE_OWN_POST);
		}
		if (userBlockService.isBlockedBetween(buyerId, post.getAuthorId())) {
			throw new BusinessException(ErrorCode.USER_BLOCKED);
		}
		if (post.getStatus() == PostStatus.EXPIRED) return new TradeOutcome.PostExpired();
		if (post.getStatus() == PostStatus.OPEN && isExpired(post)) {
			// 기존 REQUESTED 거래가 있어도 배치와 동일하게 취소·환불하고 만료시킨다.
			postExpiryProcessor.expire(postId);
			return new TradeOutcome.PostExpired();
		}
		if (tradeRepository.existsByPostIdAndStatusIn(postId,
				List.of(TradeStatus.REQUESTED, TradeStatus.ACCEPTED))) {
			throw new BusinessException(ErrorCode.TRADE_ALREADY_IN_PROGRESS);
		}
		if (post.getStatus() != PostStatus.OPEN) throw new BusinessException(ErrorCode.POST_NOT_OPEN);
		Trade trade;
		try {
			trade = tradeRepository.saveAndFlush(Trade.request(postId, buyerId, post.getAuthorId(),
				post.getPrice(), LocalDateTime.now(clock)));
		} catch (DataIntegrityViolationException concurrentRequest) {
			throw new BusinessException(ErrorCode.TRADE_ALREADY_IN_PROGRESS);
		}
		if (post.getType() == PostType.SALE) creditService.hold(buyerId, trade.getId(), trade.getPrice());
		notifiers.forEach(notifier -> notifier.notify(trade, TradeSystemEvent.TRADE_REQUESTED, buyerId));
		return new TradeOutcome.Done(TradeResult.from(trade, post.getStatus()));
	}

	@Transactional
	public TradeOutcome accept(Long userId, Long tradeId) {
		var locked = tradeLockService.lockParticipant(tradeId, userId);
		Trade trade = locked.trade();
		Post post = locked.post();
		if (!trade.isSeller(userId)) throw new BusinessException(ErrorCode.TRADE_FORBIDDEN);
		trade.requireStatus(TradeStatus.REQUESTED);
		LocalDateTime now = LocalDateTime.now(clock);
		if (post.getStatus() == PostStatus.EXPIRED || isExpired(post)) {
			trade.cancel(null, null, now);
			refund(trade);
			if (post.getStatus() == PostStatus.OPEN) postStateService.markExpired(post.getId());
			notify(trade, TradeSystemEvent.TRADE_CANCELED, null);
			return new TradeOutcome.PostExpired();
		}
		trade.accept(now);
		postStateService.markReserved(post.getId());
		notify(trade, TradeSystemEvent.TRADE_ACCEPTED, userId);
		return new TradeOutcome.Done(TradeResult.from(trade, post.getStatus()));
	}

	@Transactional
	public TradeResult reject(Long userId, Long tradeId) {
		var locked = tradeLockService.lockParticipant(tradeId, userId);
		Trade trade = locked.trade();
		if (!trade.isSeller(userId)) throw new BusinessException(ErrorCode.TRADE_FORBIDDEN);
		trade.reject(LocalDateTime.now(clock));
		refund(trade);
		notify(trade, TradeSystemEvent.TRADE_REJECTED, userId);
		return TradeResult.from(trade, locked.post().getStatus());
	}

	@Transactional
	public TradeResult cancel(Long userId, Long tradeId, String reason) {
		var locked = tradeLockService.lockParticipant(tradeId, userId);
		Trade trade = locked.trade();
		Post post = locked.post();
		trade.cancel(userId, reason, LocalDateTime.now(clock));
		refund(trade);
		if (post.getStatus() == PostStatus.RESERVED) postStateService.markOpen(post.getId());
		if (post.getStatus() == PostStatus.OPEN && isExpired(post)) postStateService.markExpired(post.getId());
		notify(trade, TradeSystemEvent.TRADE_CANCELED, userId);
		return TradeResult.from(trade, post.getStatus());
	}

	@Transactional
	public TradeCompleteResult complete(Long userId, Long tradeId) {
		var locked = tradeLockService.lockParticipant(tradeId, userId);
		if (!locked.trade().isBuyer(userId)) throw new BusinessException(ErrorCode.TRADE_FORBIDDEN);
		return completionService.complete(locked.trade(), locked.post(), userId);
	}

	private void refund(Trade trade) {
		if (trade.getPrice() > 0) creditService.refund(trade.getBuyerId(), trade.getId(), trade.getPrice());
	}

	private void notify(Trade trade, TradeSystemEvent event, Long actorId) {
		notifiers.forEach(notifier -> notifier.notify(trade, event, actorId));
	}

	private boolean isExpired(Post post) {
		LocalDate expiresOn = inventoryService.getDetail(post.getAuthorId(), post.getInventoryItemId()).expiresOn();
		return expiresOn != null && expiresOn.isBefore(LocalDate.now(clock));
	}
}
