package com.vium.trade.service;

import com.vium.credit.service.CreditService;
import com.vium.dispose.service.DisposeService;
import com.vium.dispose.service.ProportionalAmountCalculator;
import com.vium.inventory.service.InventoryService;
import com.vium.post.entity.Post;
import com.vium.post.service.PostStateService;
import com.vium.trade.dto.TradeCompleteResult;
import com.vium.trade.entity.Trade;
import com.vium.trade.entity.TradeStatus;
import com.vium.trade.repository.TradeRepository;
import com.vium.user.service.UserProfileService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TradeCompletionService {
	private final InventoryService inventoryService;
	private final DisposeService disposeService;
	private final CreditService creditService;
	private final PostStateService postStateService;
	private final UserProfileService userProfileService;
	private final TradeRepository tradeRepository;
	private final List<TradeChatNotifier> notifiers;
	private final Clock clock;

	@Transactional(propagation = Propagation.MANDATORY)
	public TradeCompleteResult complete(Trade trade, Post post, Long actorId) {
		trade.requireStatus(TradeStatus.ACCEPTED);
		LocalDateTime now = LocalDateTime.now(clock);
		var source = inventoryService.closeAsSharedForTrade(trade.getSellerId(), post.getInventoryItemId());
		// 같은 두 사용자의 동시 완료도 7일 보너스를 한 번만 받도록 직렬화한다.
		// FK INSERT의 key-share 잠금보다 먼저, 사용자 ID 순으로 잠가 역방향 거래의 교착을 피한다.
		userProfileService.lockActiveUser(Math.min(trade.getSellerId(), trade.getBuyerId()));
		userProfileService.lockActiveUser(Math.max(trade.getSellerId(), trade.getBuyerId()));
		disposeService.recordSharedEvent(trade.getSellerId(), source.inventoryItemId(), source.remainingQuantity(),
			ProportionalAmountCalculator.calculate(source.amount(), source.remainingQuantity(), source.initialQuantity()), now);
		Long buyerItemId = inventoryService.registerFromTrade(trade.getBuyerId(), source, LocalDate.now(clock));
		if (!tradeRepository.existsCompletedBetweenSince(trade.getSellerId(), trade.getBuyerId(),
				now.minusDays(7), trade.getId())) {
			creditService.grantSavingBonus(trade.getSellerId(), trade.getId());
		}
		if (trade.getPrice() > 0) creditService.payIncome(trade.getSellerId(), trade.getId(), trade.getPrice());
		trade.complete(buyerItemId, now);
		postStateService.markCompleted(post.getId());
		notifiers.forEach(notifier -> notifier.notify(trade, TradeSystemEvent.TRADE_COMPLETED, actorId));
		return TradeCompleteResult.from(trade, post.getStatus());
	}
}
