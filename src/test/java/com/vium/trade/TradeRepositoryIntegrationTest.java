package com.vium.trade;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.trade.entity.Trade;
import com.vium.trade.entity.TradeReview;
import com.vium.trade.entity.TradeStatus;
import com.vium.trade.repository.TradeRepository;
import com.vium.trade.repository.TradeReviewRepository;
import com.vium.trade.service.TradeChatNotifier;
import com.vium.trade.service.TradeSystemEvent;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class TradeRepositoryIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 9, 0);

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private TradeReviewRepository tradeReviewRepository;

	@Autowired
	private TradeChatNotifier tradeChatNotifier;

	@Autowired
	private EntityManager entityManager;

	@Test
	void persistsAndReloadsTradeWithAllFields() {
		Trade accepted = Trade.request(1L, 10L, 20L, 5000, NOW);
		accepted.accept(NOW.plusMinutes(5));
		Trade saved = tradeRepository.save(accepted);
		entityManager.flush();
		entityManager.clear();

		Trade found = tradeRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getPostId()).isEqualTo(1L);
		assertThat(found.getBuyerId()).isEqualTo(10L);
		assertThat(found.getSellerId()).isEqualTo(20L);
		assertThat(found.getStatus()).isEqualTo(TradeStatus.ACCEPTED);
		assertThat(found.getPrice()).isEqualTo(5000);
		assertThat(found.getRequestedAt()).isEqualTo(NOW);
		assertThat(found.getAcceptedAt()).isEqualTo(NOW.plusMinutes(5));
		assertThat(found.getAutoResolveAt()).isEqualTo(NOW.plusMinutes(5).plusHours(72));
		assertThat(found.getCompletedAt()).isNull();
		assertThat(found.getBuyerInventoryItemId()).isNull();
	}

	@Test
	void persistsAndReloadsTradeReview() {
		Trade completed = Trade.request(2L, 11L, 21L, 3000, NOW);
		completed.accept(NOW);
		completed.complete(null, NOW);
		Trade trade = tradeRepository.save(completed);

		TradeReview saved = tradeReviewRepository.save(
			TradeReview.create(trade.getId(), 11L, 21L, 5, "친절해요", NOW));
		entityManager.flush();
		entityManager.clear();

		TradeReview found = tradeReviewRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getTradeId()).isEqualTo(trade.getId());
		assertThat(found.getWriterId()).isEqualTo(11L);
		assertThat(found.getTargetUserId()).isEqualTo(21L);
		assertThat(found.getMannerScore()).isEqualTo((short) 5);
		assertThat(found.getContent()).isEqualTo("친절해요");
	}

	@Test
	void defaultChatNotifierIsNoOpAndDoesNotThrow() {
		Trade trade = tradeRepository.save(Trade.request(3L, 12L, 22L, 1000, NOW));

		tradeChatNotifier.notify(trade, TradeSystemEvent.TRADE_REQUESTED);
	}
}
