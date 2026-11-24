package com.vium.trade.service;

import com.vium.credit.service.CreditService;
import com.vium.post.service.PostExpiryHandler;
import com.vium.trade.entity.TradeStatus;
import com.vium.trade.repository.TradeRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TradePostExpiryHandler implements PostExpiryHandler {
	private final TradeRepository tradeRepository;
	private final CreditService creditService;
	private final List<TradeChatNotifier> notifiers;
	private final Clock clock;

	@Override
	public void beforeExpire(Long postId) {
		tradeRepository.findByPostIdAndStatusForUpdate(postId, TradeStatus.REQUESTED).ifPresent(trade -> {
			trade.cancel(null, null, LocalDateTime.now(clock));
			if (trade.getPrice() > 0) creditService.refund(trade.getBuyerId(), trade.getId(), trade.getPrice());
			notifiers.forEach(notifier -> notifier.notify(trade, TradeSystemEvent.TRADE_CANCELED, null));
		});
	}
}
