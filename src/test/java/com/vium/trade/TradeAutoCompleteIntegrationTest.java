package com.vium.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;

import com.vium.post.entity.PostType;
import com.vium.trade.service.TradeAutoCompleteService;
import com.vium.trade.service.TradeAutoCompleteProcessor;
import com.vium.trade.service.TradeAutoCompleteScheduler;
import com.vium.trade.service.TradeSystemEvent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class TradeAutoCompleteIntegrationTest extends TradeIntegrationSupport {
	@Autowired TradeAutoCompleteService auto;
	@Autowired TradeAutoCompleteProcessor processor;
	@Autowired ApplicationContext context;

	@Test
	void onlyDueAcceptedTradesCompleteAndExpiredInventoryTransfers() {
		var post = createPost(SELLER, PostType.SALE);
		long due = accepted(post, BUYER);
		expire(post);
		long future = accepted(createPost(SELLER, PostType.SHARE), BUYER);
		long requested = request(createPost(SELLER, PostType.SHARE), BUYER);
		long canceled = accepted(createPost(SELLER, PostType.SALE), BUYER);
		trades.cancel(BUYER, canceled, null);
		for (long id : new long[]{due, requested, canceled}) {
			jdbc.update("update trades set auto_resolve_at=? where id=?", LOCAL_NOW, id);
		}
		assertThat(auto.completeDueTrades()).isEqualTo(1);
		assertThat(tradeStatus(due)).isEqualTo("COMPLETED");
		assertThat(tradeStatus(future)).isEqualTo("ACCEPTED");
		assertThat(tradeStatus(requested)).isEqualTo("REQUESTED");
		assertThat(tradeStatus(canceled)).isEqualTo("CANCELED");
		long target = jdbc.queryForObject("select buyer_inventory_item_id from trades where id=?", Long.class, due);
		assertThat(items.findById(target).orElseThrow().getExpiresOn()).isEqualTo(TODAY.minusDays(1));
		assertThat(auto.completeDueTrades()).isZero();
	}

	@Test
	void failedItemDoesNotRollBackOtherItems() {
		var broken = createPost(SELLER, PostType.SALE);
		long bad = accepted(broken, BUYER);
		long good = accepted(createPost(SELLER, PostType.SALE), OTHER);
		jdbc.update("update trades set auto_resolve_at=?", LOCAL_NOW.minusSeconds(1));
		jdbc.update("update inventory_items set status_id=3 where id=?", broken.getInventoryItemId());
		assertThat(auto.completeDueTrades()).isEqualTo(1);
		assertThat(tradeStatus(bad)).isEqualTo("ACCEPTED");
		assertThat(tradeStatus(good)).isEqualTo("COMPLETED");
		assertThat(ledgerCount("TRADE_INCOME")).isEqualTo(1);
	}

	@Test
	void reminderIsSentOnlyOnceAndBoundariesAreRespected() {
		long soon = accepted(createPost(SELLER, PostType.SHARE), BUYER);
		long due = accepted(createPost(SELLER, PostType.SHARE), BUYER);
		long later = accepted(createPost(SELLER, PostType.SHARE), BUYER);
		jdbc.update("update trades set auto_resolve_at=? where id=?", LOCAL_NOW.plusHours(24), soon);
		jdbc.update("update trades set auto_resolve_at=? where id=?", LOCAL_NOW, due);
		jdbc.update("update trades set auto_resolve_at=? where id=?", LOCAL_NOW.plusHours(24).plusSeconds(1), later);
		clearInvocations(notifier);
		assertThat(auto.notifySoonTrades()).isEqualTo(1);
		assertThat(auto.notifySoonTrades()).isZero();
		assertThat(processor.notifySoon(soon)).isFalse();
		verify(notifier).notify(any(), eq(TradeSystemEvent.TRADE_AUTO_COMPLETE_SOON));
		assertThat(jdbc.queryForObject("select auto_complete_notified_at from trades where id=?", LocalDateTime.class, soon)).isEqualTo(LOCAL_NOW);
	}

	@Test
	void failedReminderCanBeRetried() {
		long id = accepted(createPost(SELLER, PostType.SHARE), BUYER);
		jdbc.update("update trades set auto_resolve_at=? where id=?", LOCAL_NOW.plusHours(1), id);
		doThrow(new IllegalStateException("failed")).when(notifier).notify(any(), eq(TradeSystemEvent.TRADE_AUTO_COMPLETE_SOON));
		assertThat(auto.notifySoonTrades()).isZero();
		assertThat(jdbc.queryForObject("select auto_complete_notified_at from trades where id=?", LocalDateTime.class, id)).isNull();
		doNothing().when(notifier).notify(any(), eq(TradeSystemEvent.TRADE_AUTO_COMPLETE_SOON));
		assertThat(auto.notifySoonTrades()).isEqualTo(1);
	}

	@Test
	void concurrentManualAndAutoCompletionPayOnce() throws Exception {
		long id = accepted(createPost(SELLER, PostType.SALE), BUYER);
		jdbc.update("update trades set auto_resolve_at=? where id=?", LOCAL_NOW, id);
		race(() -> String.valueOf(processor.complete(id)), () -> {
			try { trades.complete(BUYER, id); return "OK"; }
			catch (com.vium.global.exception.BusinessException e) { return e.getErrorCode().name(); }
		});
		assertThat(tradeStatus(id)).isEqualTo("COMPLETED");
		assertThat(ledgerCount("TRADE_INCOME")).isEqualTo(1);
		assertThat(count("consumption_events")).isEqualTo(1);
	}

	@Test
	void processorRechecksStateAndScheduleAndSchedulerDefaultsOff() {
		long id = accepted(createPost(SELLER, PostType.SHARE), BUYER);
		assertThat(processor.complete(id)).isFalse();
		trades.cancel(BUYER, id, null);
		assertThat(processor.complete(id)).isFalse();
		assertThat(processor.notifySoon(id)).isFalse();
		assertThat(context.getBeansOfType(TradeAutoCompleteScheduler.class)).isEmpty();
	}
}
