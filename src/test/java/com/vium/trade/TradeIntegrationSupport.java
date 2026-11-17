package com.vium.trade;

import static org.mockito.Mockito.when;

import com.vium.auth.service.TokenService;
import com.vium.credit.service.CreditService;
import com.vium.inventory.entity.InventoryItem;
import com.vium.inventory.repository.InventoryItemRepository;
import com.vium.inventory.service.InventoryService;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostRepository;
import com.vium.trade.service.TradeChatNotifier;
import com.vium.trade.service.TradeService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:trade-flow-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"images.storage.public-base-url=https://cdn.example.test/"
})
@AutoConfigureMockMvc
@Sql(scripts = "/db/inventory-query-support.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
abstract class TradeIntegrationSupport {
	static final long BUYER = 42L;
	static final long SELLER = 43L;
	static final long OTHER = 44L;
	static final Instant NOW = Instant.parse("2026-11-16T16:00:00Z");
	static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
	static final LocalDateTime LOCAL_NOW = TODAY.atTime(1, 0);

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokens;
	@Autowired TradeService trades;
	@Autowired CreditService credits;
	@Autowired PostRepository posts;
	@Autowired InventoryItemRepository items;
	@Autowired ObjectMapper mapper;
	@MockitoBean Clock clock;
	@MockitoBean TradeChatNotifier notifier;
	@MockitoSpyBean InventoryService inventory;

	@BeforeEach
	void seed() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
		for (String table : List.of("trade_reviews", "credit_transactions", "trades", "consumption_events",
				"post_images", "posts", "inventory_items", "user_blocks", "users", "uploaded_images",
				"ingredient_catalog", "ingredient_categories", "units", "storage_methods", "item_statuses", "event_sources")) {
			jdbc.update("delete from " + table);
		}
		jdbc.update("insert into units(id,code,name) values (1,'ea','개')");
		jdbc.update("insert into storage_methods(id,code,name) values (1,'cold','냉장')");
		jdbc.update("insert into item_statuses(id,code,name) values (1,'active','보유중'),(2,'consumed','소진'),(3,'disposed','폐기'),(4,'shared','나눔')");
		jdbc.update("insert into event_sources(id,code,name) values (1,'manual','수동')");
		jdbc.update("insert into ingredient_categories(id,name) values (1,'유제품')");
		jdbc.update("insert into ingredient_catalog(id,name,category_id,default_unit_id,post_allowed,created_at) values (1,'우유',1,1,true,CURRENT_TIMESTAMP)");
		for (long userId = 42; userId <= 48; userId++) {
			jdbc.update("insert into users(id,email,display_name,created_at,updated_at) values (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
				userId, userId + "@example.com", "User" + userId);
			jdbc.update("insert into credit_transactions(user_id,type,amount,created_at) values (?,'SIGNUP_REWARD',100,CURRENT_TIMESTAMP)", userId);
		}
	}

	Post createPost(long seller, PostType type) {
		var item = items.saveAndFlush(InventoryItem.builder().userId(seller).ingredientCatalogId(1L).customName("서울 우유")
			.statusId((short) 1).unitId((short) 1).storageMethodId((short) 1)
			.initialQuantity(new BigDecimal("2.500")).remainingQuantity(new BigDecimal("2.500"))
			.amount(new BigDecimal("5000")).purchasedOn(TODAY.minusDays(1)).expiresOn(TODAY.plusDays(1)).build());
		return posts.saveAndFlush(Post.builder().authorId(seller).inventoryItemId(item.getId()).type(type)
			.status(PostStatus.OPEN).title("우유 판매").price(type == PostType.SALE ? 3 : 0).isOpened(false)
			.latitude(37.5).longitude(127.0).createdAt(LOCAL_NOW).updatedAt(LOCAL_NOW).build());
	}
	long request(Post post, long buyer) { return trades.request(buyer, post.getId()).tradeId(); }
	long accepted(Post post, long buyer) {
		long id = request(post, buyer);
		trades.accept(post.getAuthorId(), id);
		return id;
	}
	long completed(long seller, long buyer) {
		long id = accepted(createPost(seller, PostType.SHARE), buyer);
		trades.complete(buyer, id);
		return id;
	}
	String bearer(long userId) { return "Bearer " + tokens.issue(userId).accessToken(); }
	String endpoint(long tradeId, String action) { return "/api/me/trades/" + tradeId + "/" + action; }
	String tradeStatus(long id) { return jdbc.queryForObject("select status from trades where id=?", String.class, id); }
	String postStatus(Post post) { return jdbc.queryForObject("select status from posts where id=?", String.class, post.getId()); }
	long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
	long ledgerCount(String type) { return jdbc.queryForObject("select count(*) from credit_transactions where type=?", Long.class, type); }
	void expire(Post post) { jdbc.update("update inventory_items set expires_on=? where id=?", TODAY.minusDays(1), post.getInventoryItemId()); }

	List<String> race(Callable<String> first, Callable<String> second) throws Exception {
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var futures = List.of(first, second).stream().map(action -> pool.submit(() -> {
				ready.countDown();
				if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
				return action.call();
			})).toList();
			if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("ready timeout");
			start.countDown();
			return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
		}
	}
}
