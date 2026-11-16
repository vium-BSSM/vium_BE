package com.vium.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.vium.credit.service.CreditService;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:credit-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class CreditServiceIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
	private static final Long BUYER_ID = 42L;
	private static final Long SELLER_ID = 43L;
	private static final Long MISSING_USER_ID = 999L;

	@Autowired
	private CreditService creditService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private Clock clock;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
		jdbcTemplate.update("delete from credit_transactions");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.update("""
			insert into users (id, email, display_name, password_hash, created_at, updated_at, deleted_at)
			values (42, 'buyer@example.com', 'Buyer', null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null),
			       (43, 'seller@example.com', 'Seller', null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, null),
			       (44, 'withdrawn@example.com', 'Withdrawn', null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""");
	}

	private void seedBalance(Long userId, int amount) {
		jdbcTemplate.update("""
			insert into credit_transactions (user_id, type, amount, created_at)
			values (?, 'TRADE_REFUND', ?, CURRENT_TIMESTAMP)
			""", userId, amount);
	}

	@Test
	void availableBalanceSumsAllLedgerEntries() {
		seedBalance(BUYER_ID, 1000);
		seedBalance(BUYER_ID, -300);

		assertThat(creditService.getAvailableBalance(BUYER_ID)).isEqualTo(700);
	}

	@Test
	void availableBalanceIsZeroWithoutEntries() {
		assertThat(creditService.getAvailableBalance(BUYER_ID)).isZero();
	}

	@Test
	void holdThenRefundRestoresBalance() {
		seedBalance(BUYER_ID, 1000);

		creditService.hold(BUYER_ID, 1L, 400);
		assertThat(creditService.getAvailableBalance(BUYER_ID)).isEqualTo(600);

		creditService.refund(BUYER_ID, 1L, 400);
		assertThat(creditService.getAvailableBalance(BUYER_ID)).isEqualTo(1000);
	}

	@Test
	void holdWithInsufficientBalanceThrowsAndLeavesLedgerUnchanged() {
		seedBalance(BUYER_ID, 100);

		assertThatThrownBy(() -> creditService.hold(BUYER_ID, 1L, 500))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException) ex).getErrorCode())
			.isEqualTo(ErrorCode.INSUFFICIENT_CREDIT);

		assertThat(creditService.getAvailableBalance(BUYER_ID)).isEqualTo(100);
		assertThat(countTransactions(BUYER_ID)).isEqualTo(1);
	}

	@Test
	void payIncomeAddsCreditToSeller() {
		creditService.payIncome(SELLER_ID, 1L, 300);

		assertThat(creditService.getAvailableBalance(SELLER_ID)).isEqualTo(300);
	}

	@Test
	void deductRejectsNonAiReportType() {
		seedBalance(BUYER_ID, 1000);

		assertThatThrownBy(() -> creditService.deduct(BUYER_ID, 100,
				com.vium.credit.entity.CreditTransactionType.TRADE_HOLD))
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(countTransactions(BUYER_ID)).isEqualTo(1);
	}

	@Test
	void deductWithInsufficientBalanceThrowsAndLeavesLedgerUnchanged() {
		assertThatThrownBy(() -> creditService.deduct(BUYER_ID, 100,
				com.vium.credit.entity.CreditTransactionType.AI_REPORT))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException) ex).getErrorCode())
			.isEqualTo(ErrorCode.INSUFFICIENT_CREDIT);

		assertThat(countTransactions(BUYER_ID)).isZero();
	}

	@Test
	void grantSavingBonusStopsAtDailyLimitOfThree() {
		assertThat(creditService.grantSavingBonus(SELLER_ID, 1L)).isTrue();
		assertThat(creditService.grantSavingBonus(SELLER_ID, 2L)).isTrue();
		assertThat(creditService.grantSavingBonus(SELLER_ID, 3L)).isTrue();
		assertThat(creditService.grantSavingBonus(SELLER_ID, 4L)).isFalse();

		assertThat(creditService.getAvailableBalance(SELLER_ID)).isEqualTo(3);
		assertThat(countTransactions(SELLER_ID)).isEqualTo(3);
	}

	@Test
	void operationsOnMissingOrWithdrawnUserThrowUserNotFound() {
		assertThatThrownBy(() -> creditService.hold(MISSING_USER_ID, 1L, 100))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException) ex).getErrorCode())
			.isEqualTo(ErrorCode.USER_NOT_FOUND);

		assertThatThrownBy(() -> creditService.grantSavingBonus(44L, 1L))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException) ex).getErrorCode())
			.isEqualTo(ErrorCode.USER_NOT_FOUND);
	}

	@Test
	void concurrentHoldsAllowOnlyOneWhenCombinedAmountExceedsBalance() throws Exception {
		seedBalance(BUYER_ID, 1000);

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Callable<Void> holdForTrade1 = () -> {
				ready.countDown();
				start.await();
				creditService.hold(BUYER_ID, 1L, 700);
				return null;
			};
			Callable<Void> holdForTrade2 = () -> {
				ready.countDown();
				start.await();
				creditService.hold(BUYER_ID, 2L, 700);
				return null;
			};
			Future<Void> first = executor.submit(holdForTrade1);
			Future<Void> second = executor.submit(holdForTrade2);

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			int successCount = 0;
			int rejectedCount = 0;
			for (Future<Void> future : List.of(first, second)) {
				try {
					future.get(10, TimeUnit.SECONDS);
					successCount++;
				} catch (ExecutionException e) {
					assertThat(e.getCause()).isInstanceOf(BusinessException.class);
					assertThat(((BusinessException) e.getCause()).getErrorCode())
						.isEqualTo(ErrorCode.INSUFFICIENT_CREDIT);
					rejectedCount++;
				}
			}

			assertThat(successCount).isEqualTo(1);
			assertThat(rejectedCount).isEqualTo(1);
		} finally {
			executor.shutdown();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}

		assertThat(creditService.getAvailableBalance(BUYER_ID)).isEqualTo(300);
		assertThat(countTransactionsByType(BUYER_ID, "TRADE_HOLD")).isEqualTo(1);
	}

	private Integer countTransactions(Long userId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from credit_transactions where user_id = ?", Integer.class, userId);
	}

	private Integer countTransactionsByType(Long userId, String type) {
		return jdbcTemplate.queryForObject(
			"select count(*) from credit_transactions where user_id = ? and type = ?", Integer.class, userId, type);
	}
}
