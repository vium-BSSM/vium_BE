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
		Trade saved = tradeRepository.save(Trade.builder()
			.postId(1L)
			.buyerId(10L)
			.sellerId(20L)
			.status(TradeStatus.ACCEPTED)
			.price(5000)
			.canceledById(null)
			.cancelReason(null)
			.requestedAt(NOW)
			.acceptedAt(NOW.plusMinutes(5))
			.autoResolveAt(NOW.plusHours(72))
			.completedAt(null)
			.closedAt(null)
			.build());
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
		assertThat(found.getAutoResolveAt()).isEqualTo(NOW.plusHours(72));
		assertThat(found.getCompletedAt()).isNull();
		assertThat(found.getBuyerInventoryItemId()).isNull();
	}

	@Test
	void persistsAndReloadsTradeReview() {
		Trade trade = tradeRepository.save(Trade.builder()
			.postId(2L)
			.buyerId(11L)
			.sellerId(21L)
			.status(TradeStatus.COMPLETED)
			.price(3000)
			.requestedAt(NOW)
			.build());

		TradeReview saved = tradeReviewRepository.save(TradeReview.builder()
			.tradeId(trade.getId())
			.writerId(11L)
			.targetUserId(21L)
			.mannerScore((short) 5)
			.content("친절해요")
			.createdAt(NOW)
			.build());
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
		Trade trade = tradeRepository.save(Trade.builder()
			.postId(3L)
			.buyerId(12L)
			.sellerId(22L)
			.status(TradeStatus.REQUESTED)
			.price(1000)
			.requestedAt(NOW)
			.build());

		tradeChatNotifier.notify(trade, TradeSystemEvent.TRADE_REQUESTED);
	}
}
