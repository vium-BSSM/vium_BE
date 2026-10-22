package com.vium.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vium.global.code.PurchaseSourceRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.receipt.entity.Purchase;
import com.vium.receipt.entity.PurchaseItem;
import com.vium.receipt.entity.PurchaseStatus;
import com.vium.receipt.entity.PurchaseType;
import com.vium.receipt.repository.PurchaseItemRepository;
import com.vium.receipt.repository.PurchaseQueryRepository;
import com.vium.receipt.repository.PurchaseRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class PurchaseRepositoryIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 9, 0);
	@Autowired PurchaseRepository purchases;
	@Autowired PurchaseItemRepository items;
	@Autowired PurchaseQueryRepository queries;
	@Autowired PurchaseSourceRepository sources;
	@Autowired EntityManager entityManager;
	@Autowired JdbcTemplate jdbcTemplate;

	@Test
	void persistsDraftAndItemsAndRestrictsOwnerLookup() {
		var purchase = draft(101L, PurchaseType.RECEIPT, NOW);
		var first = items.save(item(purchase.getId(), "우유", "1.125"));
		var second = items.save(item(purchase.getId(), "두부", "300"));
		entityManager.flush();
		entityManager.clear();

		var found = purchases.findByIdAndUserId(purchase.getId(), 101L).orElseThrow();
		assertThat(found.isDraft()).isTrue();
		assertThat(found.getConfirmedAt()).isNull();
		assertThat(found.getImageId()).isNull();
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
		assertThat(purchases.findByIdAndUserId(purchase.getId(), 102L)).isEmpty();
		assertThat(purchases.findByIdAndUserIdForUpdate(purchase.getId(), 102L)).isEmpty();
		assertThat(purchases.findByIdAndUserIdForUpdate(purchase.getId(), 101L)).isPresent();
		assertThat(items.findAllByPurchaseIdOrderByIdAsc(purchase.getId()))
			.extracting(PurchaseItem::getId).containsExactly(first.getId(), second.getId());
		assertThat(items.findById(first.getId()).orElseThrow().getQuantity()).isEqualByComparingTo("1.125");

		var other = draft(101L, PurchaseType.ONLINE_ORDER, NOW);
		items.save(item(other.getId(), "감자", "2"));
		items.deleteAllByPurchaseId(purchase.getId());
		entityManager.flush();
		assertThat(items.findAllByPurchaseIdOrderByIdAsc(purchase.getId())).isEmpty();
		assertThat(items.findAllByPurchaseIdOrderByIdAsc(other.getId())).hasSize(1);
	}

	@Test
	void confirmsOnceAndPreservesFirstConfirmationOnRetry() {
		var purchase = draft(101L, PurchaseType.RECEIPT, NOW);
		purchase.confirm((short) 2, NOW.minusDays(1), new BigDecimal("3500"), NOW.plusMinutes(1));
		entityManager.flush();
		entityManager.clear();
		var confirmed = purchases.findById(purchase.getId()).orElseThrow();
		assertThat(confirmed.getStatus()).isEqualTo(PurchaseStatus.CONFIRMED);
		assertThat(confirmed.getConfirmedAt()).isEqualTo(NOW.plusMinutes(1));
		assertThatThrownBy(() -> confirmed.confirm(null, NOW, BigDecimal.ZERO, NOW.plusMinutes(2)))
			.isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PURCHASE_ALREADY_CONFIRMED));
		assertThat(confirmed.getPurchaseSourceId()).isEqualTo((short) 2);
		assertThat(confirmed.getPurchasedAt()).isEqualTo(NOW.minusDays(1));
		assertThat(confirmed.getTotalAmount()).isEqualByComparingTo("3500");
	}

	@Test
	void listsWithOptionalFiltersStableOrderAndItemCounts() {
		jdbcTemplate.update("insert into purchase_sources (id, code, name) values (?, ?, ?)",
			(short) 101, "test-shop", "테스트 구매처");
		var old = draft(101L, PurchaseType.RECEIPT, NOW.minusDays(1));
		var first = draft(101L, PurchaseType.RECEIPT, NOW);
		var second = draft(101L, PurchaseType.ONLINE_ORDER, NOW);
		second.confirm((short) 101, NOW, new BigDecimal("5000"), NOW);
		var boundary = draft(101L, PurchaseType.RECEIPT, NOW.toLocalDate().plusDays(1).atStartOfDay());
		draft(102L, PurchaseType.RECEIPT, NOW);
		items.save(item(second.getId(), "우유", "1"));
		items.save(item(second.getId(), "두부", "2"));
		entityManager.flush();

		assertThat(queries.findPurchases(101L, null, null, null, null))
			.extracting(row -> row.purchaseId())
			.containsExactly(boundary.getId(), second.getId(), first.getId(), old.getId());
		assertThat(queries.findPurchases(101L, PurchaseType.ONLINE_ORDER, PurchaseStatus.CONFIRMED,
			NOW.toLocalDate().atStartOfDay(), NOW.toLocalDate().plusDays(1).atStartOfDay()))
			.singleElement().satisfies(row -> {
				assertThat(row.purchaseId()).isEqualTo(second.getId());
				assertThat(row.itemCount()).isEqualTo(2);
				assertThat(row.purchaseSourceName()).isEqualTo("테스트 구매처");
			});
		assertThat(queries.findPurchases(101L, PurchaseType.RECEIPT, PurchaseStatus.DRAFT,
			NOW, NOW.toLocalDate().plusDays(1).atStartOfDay()))
			.singleElement().satisfies(row -> {
				assertThat(row.purchaseId()).isEqualTo(first.getId());
				assertThat(row.itemCount()).isZero();
				assertThat(row.purchaseSourceName()).isNull();
				assertThat(row.totalAmount()).isNull();
			});
		assertThat(sources.findByCode("test-shop")).isPresent();
		assertThat(sources.existsById((short) 101)).isTrue();
	}

	private Purchase draft(Long userId, PurchaseType type, LocalDateTime purchasedAt) {
		return purchases.save(Purchase.draft(userId, type, null, null, null, purchasedAt, NOW));
	}

	private PurchaseItem item(Long purchaseId, String name, String quantity) {
		return PurchaseItem.builder().purchaseId(purchaseId).rawName(name)
			.quantity(new BigDecimal(quantity)).unitId((short) 1).storageMethodId((short) 1)
			.expiresOn(NOW.toLocalDate().plusDays(7)).build();
	}
}
