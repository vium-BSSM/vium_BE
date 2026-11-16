package com.vium.trade.service;

import com.vium.trade.entity.TradeStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TradeAutoCompleteProcessor {
	private final TradeLockService lockService;
	private final TradeCompletionService completionService;
	private final List<TradeChatNotifier> notifiers;
	private final Clock clock;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean complete(Long tradeId) {
		var locked = lockService.lockSystem(tradeId);
		var trade = locked.trade();
		if (trade.getStatus() != TradeStatus.ACCEPTED || trade.getAutoResolveAt().isAfter(LocalDateTime.now(clock))) {
			return false;
		}
		completionService.complete(trade, locked.post());
		return true;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean notifySoon(Long tradeId) {
		var trade = lockService.lockSystem(tradeId).trade();
		LocalDateTime now = LocalDateTime.now(clock);
		if (trade.getStatus() != TradeStatus.ACCEPTED || trade.getAutoCompleteNotifiedAt() != null
				|| !trade.getAutoResolveAt().isAfter(now) || trade.getAutoResolveAt().isAfter(now.plusHours(24))) {
			return false;
		}
		trade.markAutoCompleteNotified(now);
		notifiers.forEach(notifier -> notifier.notify(trade, TradeSystemEvent.TRADE_AUTO_COMPLETE_SOON));
		return true;
	}
}
