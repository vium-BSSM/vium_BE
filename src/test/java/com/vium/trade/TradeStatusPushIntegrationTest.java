package com.vium.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vium.auth.service.TokenService;
import com.vium.inventory.entity.InventoryItem;
import com.vium.inventory.repository.InventoryItemRepository;
import com.vium.notification.service.FcmPushClient;
import com.vium.notification.service.PushResult;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import com.vium.trade.dto.TradeResult;
import com.vium.trade.entity.TradeStatus;
import com.vium.trade.service.TradeAutoCompleteProcessor;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 거래 상태 변경 FCM 푸시(이슈 #198)의 end-to-end 동작을 실제 트랜잭션 커밋 기반으로 확인한다.
 * TradeIntegrationSupport는 TradeChatNotifier를 @MockitoBean으로 대체하므로 여기서는 쓰지 않고,
 * 실제 ChatTradeNotifier·TradeStatusPushListener·TradePushService 빈이 모두 참여하게 둔다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:trade-status-push-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"images.storage.public-base-url=https://cdn.example.test/"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class TradeStatusPushIntegrationTest {

	private static final long BUYER = 42L;
	private static final long SELLER = 43L;
	private static final Instant NOW = Instant.parse("2026-11-16T16:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
	private static final LocalDateTime LOCAL_NOW = TODAY.atTime(1, 0);

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private TokenService tokens;
	@Autowired private TradeService trades;
	@Autowired private TradeAutoCompleteProcessor processor;
	@Autowired private PostRepository posts;
	@Autowired private InventoryItemRepository items;

	@MockitoBean private Clock clock;
	@MockitoBean private FcmPushClient fcmPushClient;

	@BeforeEach
	void seed() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
		for (String table : List.of("chat_messages", "chat_participants", "chat_rooms", "trade_reviews",
				"credit_transactions", "trades", "consumption_events", "post_images", "posts", "inventory_items",
				"user_blocks", "\"user_push_tokens\"", "\"users\"", "uploaded_images", "ingredient_catalog",
				"ingredient_categories", "units", "storage_methods", "item_statuses", "event_sources")) {
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
				"insert into \"users\"(id,email,display_name,created_at,updated_at) values (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
				userId, userId + "@example.com", "User" + userId);
			jdbc.update("insert into credit_transactions(user_id,type,amount,created_at) values (?,'SIGNUP_REWARD',100,CURRENT_TIMESTAMP)",
				userId);
		}
		reset(fcmPushClient);
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(PushResult.SUCCESS);
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

	private long request(Post post, long buyer) {
		return trades.request(buyer, post.getId()).tradeId();
	}

	private long accepted(Post post, long buyer) {
		long id = request(post, buyer);
		trades.accept(post.getAuthorId(), id);
		return id;
	}

	private void insertToken(long userId, String token) {
		jdbc.update("""
			insert into "user_push_tokens" (user_id, token, platform, created_at, updated_at)
			values (?, ?, 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""", userId, token);
	}

	private void setPushEnabled(long userId, boolean enabled) {
		jdbc.update("update \"users\" set push_enabled = ? where id = ?", enabled, userId);
	}

	private void setWithdrawn(long userId) {
		jdbc.update("update \"users\" set deleted_at = CURRENT_TIMESTAMP where id = ?", userId);
	}

	private String tradeStatus(long id) {
		return jdbc.queryForObject("select status from trades where id=?", String.class, id);
	}

	private String bearer(long userId) {
		return "Bearer " + tokens.issue(userId).accessToken();
	}

	@Test
	void acceptNotifiesBuyerOnlyNotTheSellerWhoAccepted() {
		insertToken(BUYER, "buyer-token");
		insertToken(SELLER, "seller-token");
		Post post = createPost(SELLER, PostType.SHARE);
		long id = request(post, BUYER);
		reset(fcmPushClient);
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(PushResult.SUCCESS);

		trades.accept(SELLER, id);

		verify(fcmPushClient, timeout(2000)).send(eq("buyer-token"), eq("거래 알림"), eq("거래가 수락되었어요."), anyMap());
		verify(fcmPushClient, after(300).never()).send(eq("seller-token"), any(), eq("거래가 수락되었어요."), any());
	}

	@Test
	void requestNotifiesSellerOnlyNotTheBuyerWhoRequested() {
		insertToken(BUYER, "buyer-token");
		insertToken(SELLER, "seller-token");
		Post post = createPost(SELLER, PostType.SHARE);

		request(post, BUYER);

		verify(fcmPushClient, timeout(2000)).send(eq("seller-token"), eq("거래 알림"), eq("거래 요청이 도착했어요."), anyMap());
		verify(fcmPushClient, after(300).never()).send(eq("buyer-token"), any(), eq("거래 요청이 도착했어요."), any());
	}

	@Test
	void rejectNotifiesBuyerOnly() {
		insertToken(BUYER, "buyer-token");
		Post post = createPost(SELLER, PostType.SHARE);
		long id = request(post, BUYER);
		reset(fcmPushClient);
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(PushResult.SUCCESS);

		trades.reject(SELLER, id);

		verify(fcmPushClient, timeout(2000)).send(eq("buyer-token"), eq("거래 알림"), eq("거래가 거절되었어요."), anyMap());
	}

	@Test
	void cancelByBuyerNotifiesSellerOnly() {
		insertToken(SELLER, "seller-token");
		Post post = createPost(SELLER, PostType.SHARE);
		long id = request(post, BUYER);
		reset(fcmPushClient);
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(PushResult.SUCCESS);

		trades.cancel(BUYER, id, null);

		verify(fcmPushClient, timeout(2000)).send(eq("seller-token"), eq("거래 알림"), eq("거래가 취소되었어요."), anyMap());
	}

	@Test
	void manualCompleteNotifiesSellerOnlyNotTheBuyerWhoCompleted() {
		insertToken(BUYER, "buyer-token");
		insertToken(SELLER, "seller-token");
		Post post = createPost(SELLER, PostType.SHARE);
		long id = accepted(post, BUYER);
		reset(fcmPushClient);
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(PushResult.SUCCESS);

		trades.complete(BUYER, id);

		verify(fcmPushClient, timeout(2000)).send(eq("seller-token"), eq("거래 알림"), eq("거래가 완료되었어요."), anyMap());
		verify(fcmPushClient, after(300).never()).send(eq("buyer-token"), any(), eq("거래가 완료되었어요."), any());
	}

	@Test
	void autoCompleteNotifiesBothBuyerAndSeller() {
		insertToken(BUYER, "buyer-token");
		insertToken(SELLER, "seller-token");
		Post post = createPost(SELLER, PostType.SHARE);
		long id = accepted(post, BUYER);
		jdbc.update("update trades set auto_resolve_at=? where id=?", LOCAL_NOW, id);
		reset(fcmPushClient);
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(PushResult.SUCCESS);

		assertThat(processor.complete(id)).isTrue();

		verify(fcmPushClient, timeout(2000)).send(eq("buyer-token"), eq("거래 알림"), eq("거래가 완료되었어요."), anyMap());
		verify(fcmPushClient, timeout(2000)).send(eq("seller-token"), eq("거래 알림"), eq("거래가 완료되었어요."), anyMap());
	}

	@Test
	void autoCompleteNotifySoonNotifiesBothBuyerAndSeller() {
		insertToken(BUYER, "buyer-token");
		insertToken(SELLER, "seller-token");
		Post post = createPost(SELLER, PostType.SHARE);
		long id = accepted(post, BUYER);
		jdbc.update("update trades set auto_resolve_at=? where id=?", LOCAL_NOW.plusHours(24), id);
		reset(fcmPushClient);
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(PushResult.SUCCESS);

		assertThat(processor.notifySoon(id)).isTrue();

		verify(fcmPushClient, timeout(2000)).send(eq("buyer-token"), eq("거래 알림"),
			eq("24시간 뒤 거래가 자동으로 완료돼요."), anyMap());
		verify(fcmPushClient, timeout(2000)).send(eq("seller-token"), eq("거래 알림"),
			eq("24시간 뒤 거래가 자동으로 완료돼요."), anyMap());
	}

	@Test
	void acceptOnExpiredPostNotifiesBothBuyerAndSellerEvenThoughSellerGetsAnErrorResponse() throws Exception {
		insertToken(BUYER, "buyer-token");
		insertToken(SELLER, "seller-token");
		Post post = createPost(SELLER, PostType.SALE);
		long id = request(post, BUYER);
		jdbc.update("update inventory_items set expires_on=? where id=?", TODAY.minusDays(1), post.getInventoryItemId());
		reset(fcmPushClient);
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(PushResult.SUCCESS);

		mvc.perform(post("/api/me/trades/" + id + "/accept").header("Authorization", bearer(SELLER)))
			.andExpect(status().isConflict());

		assertThat(tradeStatus(id)).isEqualTo("CANCELED");
		verify(fcmPushClient, timeout(2000)).send(eq("buyer-token"), eq("거래 알림"), eq("거래가 취소되었어요."), anyMap());
		verify(fcmPushClient, timeout(2000)).send(eq("seller-token"), eq("거래 알림"), eq("거래가 취소되었어요."), anyMap());
	}

	@Test
	void skipsPushWhenRecipientPushDisabled() {
		insertToken(SELLER, "seller-token");
		setPushEnabled(SELLER, false);
		Post post = createPost(SELLER, PostType.SHARE);

		request(post, BUYER);

		verify(fcmPushClient, after(300).never()).send(eq("seller-token"), any(), any(), any());
	}

	@Test
	void skipsPushWhenRecipientWithdrawnAndRequestStillSucceeds() throws Exception {
		insertToken(SELLER, "seller-token");
		setWithdrawn(SELLER);
		Post post = createPost(SELLER, PostType.SHARE);

		mvc.perform(post("/api/posts/" + post.getId() + "/trades").header("Authorization", bearer(BUYER)))
			.andExpect(status().isOk());

		verify(fcmPushClient, after(300).never()).send(eq("seller-token"), any(), any(), any());
	}

	@Test
	void pushFailureDoesNotRollBackTheTrade() throws Exception {
		insertToken(SELLER, "seller-token");
		Post post = createPost(SELLER, PostType.SHARE);
		reset(fcmPushClient);
		when(fcmPushClient.send(anyString(), anyString(), anyString(), anyMap()))
			.thenThrow(new RuntimeException("fcm down"));

		mvc.perform(post("/api/posts/" + post.getId() + "/trades").header("Authorization", bearer(BUYER)))
			.andExpect(status().isOk());

		verify(fcmPushClient, timeout(2000)).send(eq("seller-token"), any(), any(), any());
		long id = jdbc.queryForObject("select id from trades where post_id=?", Long.class, post.getId());
		assertThat(tradeStatus(id)).isEqualTo("REQUESTED");
	}

	@Test
	void noTokenDoesNotThrow() {
		Post post = createPost(SELLER, PostType.SHARE);

		TradeResult result = trades.request(BUYER, post.getId());

		assertThat(result.status()).isEqualTo(TradeStatus.REQUESTED);
		verify(fcmPushClient, never()).send(any(), any(), any(), any());
	}
}
