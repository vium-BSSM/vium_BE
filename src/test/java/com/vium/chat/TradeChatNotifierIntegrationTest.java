package com.vium.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.vium.auth.service.TokenService;
import com.vium.chat.repository.ChatMessageRepository;
import com.vium.chat.service.ChatTradeNotifier;
import com.vium.inventory.entity.InventoryItem;
import com.vium.inventory.repository.InventoryItemRepository;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import com.vium.trade.dto.TradeResult;
import com.vium.trade.service.TradeAutoCompleteProcessor;
import com.vium.trade.service.TradeChatNotifier;
import com.vium.trade.service.TradeService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;

/**
 * TradeIntegrationSupport는 TradeChatNotifier를 @MockitoBean으로 대체해 다른 거래 테스트를 chat과 분리한다.
 * 이 클래스는 일부러 그 베이스를 쓰지 않고 실제 ChatTradeNotifier 빈이 참여하도록 둔다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:trade-chat-notifier-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"images.storage.public-base-url=https://cdn.example.test/"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class TradeChatNotifierIntegrationTest {

	private static final long BUYER = 42L;
	private static final long SELLER = 43L;
	private static final Instant NOW = Instant.parse("2026-11-16T16:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
	private static final LocalDateTime LOCAL_NOW = TODAY.atTime(1, 0);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private TradeService trades;

	@Autowired
	private TradeAutoCompleteProcessor processor;

	@Autowired
	private PostRepository posts;

	@Autowired
	private InventoryItemRepository items;

	@Autowired
	private ApplicationContext context;

	@MockitoBean
	private Clock clock;

	@MockitoSpyBean
	private ChatMessageRepository chatMessageRepository;

	@BeforeEach
	void seed() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
		for (String table : List.of("chat_messages", "chat_participants", "chat_rooms", "trade_reviews",
				"credit_transactions", "trades", "consumption_events", "post_images", "posts", "inventory_items",
				"user_blocks", "users", "uploaded_images", "ingredient_catalog", "ingredient_categories", "units",
				"storage_methods", "item_statuses", "event_sources")) {
			jdbc.update("delete from " + table);
		}
		jdbc.update("insert into units(id,code,name) values (1,'ea','개')");
		jdbc.update("insert into storage_methods(id,code,name) values (1,'cold','냉장')");
		jdbc.update("""
			insert into item_statuses(id,code,name) values
			(1,'active','보유중'),(2,'consumed','소진'),(3,'disposed','폐기'),(4,'shared','나눔')
			""");
		jdbc.update("insert into event_sources(id,code,name) values (1,'manual','수동')");
		jdbc.update("insert into ingredient_categories(id,name) values (1,'유제품')");
		jdbc.update("""
			insert into ingredient_catalog(id,name,category_id,default_unit_id,post_allowed,created_at)
			values (1,'우유',1,1,true,CURRENT_TIMESTAMP)
			""");
		for (long userId : new long[] {BUYER, SELLER}) {
			jdbc.update(
				"insert into users(id,email,display_name,created_at,updated_at) values (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
				userId, userId + "@example.com", "User" + userId);
			jdbc.update("insert into credit_transactions(user_id,type,amount,created_at) values (?,'SIGNUP_REWARD',100,CURRENT_TIMESTAMP)",
				userId);
		}
	}

	private Post createPost(long seller, PostType type) {
		var item = items.saveAndFlush(InventoryItem.builder().userId(seller).ingredientCatalogId(1L)
			.customName("서울 우유").statusId((short) 1).unitId((short) 1).storageMethodId((short) 1)
			.initialQuantity(new BigDecimal("2.500")).remainingQuantity(new BigDecimal("2.500"))
			.amount(new BigDecimal("5000")).purchasedOn(TODAY.minusDays(1)).expiresOn(TODAY.plusDays(1)).build());
		return posts.saveAndFlush(Post.builder().authorId(seller).inventoryItemId(item.getId()).type(type)
			.status(PostStatus.OPEN).title("우유 판매").price(type == PostType.SALE ? 3 : 0).isOpened(false)
			.latitude(37.5).longitude(127.0).createdAt(LOCAL_NOW).updatedAt(LOCAL_NOW).build());
	}

	private Long chatRoomId(Long postId, long buyerId) {
		return jdbc.queryForObject("select id from chat_rooms where post_id = ? and buyer_id = ?", Long.class,
			postId, buyerId);
	}

	private String lastSystemContent(Long chatRoomId) {
		return jdbc.queryForObject(
			"select content from chat_messages where chat_room_id = ? order by id desc limit 1", String.class,
			chatRoomId);
	}

	private String lastSystemEvent(Long chatRoomId) {
		return jdbc.queryForObject(
			"select system_event from chat_messages where chat_room_id = ? order by id desc limit 1", String.class,
			chatRoomId);
	}

	private void assertBothParticipantsJoined(Long chatRoomId) {
		Integer joined = jdbc.queryForObject(
			"select count(*) from chat_participants where chat_room_id = ? and left_at is null", Integer.class,
			chatRoomId);
		assertThat(joined).isEqualTo(2);
	}

	@Test
	void chatTradeNotifierBeanReplacesNoOp() {
		assertThat(context.getBeansOfType(TradeChatNotifier.class)).hasSize(1);
		assertThat(context.getBean(TradeChatNotifier.class)).isInstanceOf(ChatTradeNotifier.class);
	}

	@Test
	void requestedAcceptedCompletedProduceSystemMessagesOnSameRoom() {
		Post post = createPost(SELLER, PostType.SHARE);
		TradeResult requested = trades.request(BUYER, post.getId());
		Long roomId = chatRoomId(post.getId(), BUYER);
		assertThat(lastSystemContent(roomId)).isEqualTo("거래 요청이 도착했어요.");
		assertThat(lastSystemEvent(roomId)).isEqualTo("TRADE_REQUESTED");
		assertBothParticipantsJoined(roomId);

		trades.accept(SELLER, requested.tradeId());
		assertThat(lastSystemContent(roomId)).isEqualTo("거래가 수락되었어요.");
		assertThat(lastSystemEvent(roomId)).isEqualTo("TRADE_ACCEPTED");

		trades.complete(BUYER, requested.tradeId());
		assertThat(lastSystemContent(roomId)).isEqualTo("거래가 완료되었어요.");
		assertThat(lastSystemEvent(roomId)).isEqualTo("TRADE_COMPLETED");

		Integer messageCount = jdbc.queryForObject(
			"select count(*) from chat_messages where chat_room_id = ? and type = 'SYSTEM' and sender_id is null",
			Integer.class, roomId);
		assertThat(messageCount).isEqualTo(3);
	}

	@Test
	void rejectedProducesSystemMessage() {
		Post post = createPost(SELLER, PostType.SHARE);
		TradeResult requested = trades.request(BUYER, post.getId());
		Long roomId = chatRoomId(post.getId(), BUYER);

		trades.reject(SELLER, requested.tradeId());
		assertThat(lastSystemContent(roomId)).isEqualTo("거래가 거절되었어요.");
		assertThat(lastSystemEvent(roomId)).isEqualTo("TRADE_REJECTED");
	}

	@Test
	void canceledProducesSystemMessage() {
		Post post = createPost(SELLER, PostType.SHARE);
		TradeResult requested = trades.request(BUYER, post.getId());
		Long roomId = chatRoomId(post.getId(), BUYER);

		trades.cancel(BUYER, requested.tradeId(), null);
		assertThat(lastSystemContent(roomId)).isEqualTo("거래가 취소되었어요.");
		assertThat(lastSystemEvent(roomId)).isEqualTo("TRADE_CANCELED");
	}

	@Test
	void autoCompleteSoonProducesSystemMessageAndRestoresLeftParticipant() {
		Post post = createPost(SELLER, PostType.SHARE);
		TradeResult requested = trades.request(BUYER, post.getId());
		trades.accept(SELLER, requested.tradeId());
		Long roomId = chatRoomId(post.getId(), BUYER);
		jdbc.update("update trades set auto_resolve_at = ? where id = ?", LOCAL_NOW.plusHours(24), requested.tradeId());
		jdbc.update("update chat_participants set left_at = CURRENT_TIMESTAMP where chat_room_id = ? and user_id = ?",
			roomId, BUYER);

		assertThat(processor.notifySoon(requested.tradeId())).isTrue();
		assertThat(lastSystemContent(roomId)).isEqualTo("24시간 뒤 거래가 자동으로 완료돼요.");
		assertThat(lastSystemEvent(roomId)).isEqualTo("TRADE_AUTO_COMPLETE_SOON");
		assertBothParticipantsJoined(roomId);
	}

	@Test
	void chatWriteFailureRollsBackWholeTradeTransaction() {
		Post post = createPost(SELLER, PostType.SALE);
		doThrow(new RuntimeException("chat write failed")).when(chatMessageRepository).save(any());

		assertThatThrownBy(() -> trades.request(BUYER, post.getId())).isInstanceOf(RuntimeException.class);

		assertThat(jdbc.queryForObject("select count(*) from trades where post_id = ?", Integer.class, post.getId()))
			.isZero();
		assertThat(jdbc.queryForObject(
			"select count(*) from credit_transactions where user_id = ? and type = 'TRADE_HOLD'", Integer.class, BUYER))
			.isZero();
		assertThat(jdbc.queryForObject("select count(*) from chat_rooms where post_id = ?", Integer.class, post.getId()))
			.isZero();
		assertThat(jdbc.queryForObject("select count(*) from chat_messages", Integer.class)).isZero();
	}
}
