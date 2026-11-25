package com.vium.ingredient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.vium.global.code.StorageMethodRepository;
import com.vium.ingredient.entity.ExpiryEstimationRule;
import com.vium.ingredient.repository.ExpiryEstimationRuleRepository;
import com.vium.ingredient.service.ExpiryEstimationService.ExpiryEstimate;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpiryEstimationServiceTest {

	private static final long CATALOG_ID = 5;
	private static final short COLD = 1;
	private static final short FROZEN = 2;
	private static final short ROOM = 3;
	private static final LocalDate DATE = LocalDate.of(2026, 1, 15);

	private final ExpiryEstimationRuleRepository rules = mock(ExpiryEstimationRuleRepository.class);
	private final ExpiryEstimationService service = new ExpiryEstimationService(rules,
		mock(IngredientCatalogService.class), mock(StorageMethodRepository.class));

	@Test
	void usesOnlyTheRequestedStorageMethodAndSelectsFirstApplicableRule() {
		var applicableRules = List.of(rule(90), rule(30));
		when(rules.findApplicableRules(CATALOG_ID, FROZEN, DATE)).thenReturn(applicableRules);

		assertThat(service.estimate(CATALOG_ID, FROZEN, DATE))
			.contains(new ExpiryEstimate(FROZEN, 90, DATE.plusDays(90)));
		verify(rules).findApplicableRules(CATALOG_ID, FROZEN, DATE);
		verifyNoMoreInteractions(rules);
	}

	@Test
	void prefersRoomTemperatureOverFrozenWhenColdHasNoRule() {
		var roomRules = List.of(rule(30));
		var frozenRules = List.of(rule(90));
		when(rules.findApplicableRules(CATALOG_ID, ROOM, DATE)).thenReturn(roomRules);
		when(rules.findApplicableRules(CATALOG_ID, FROZEN, DATE)).thenReturn(frozenRules);

		assertThat(service.estimateWithDefaultStorage(CATALOG_ID, DATE))
			.contains(new ExpiryEstimate(ROOM, 30, DATE.plusDays(30)));
		var order = inOrder(rules);
		order.verify(rules).findApplicableRules(CATALOG_ID, COLD, DATE);
		order.verify(rules).findApplicableRules(CATALOG_ID, ROOM, DATE);
		verifyNoMoreInteractions(rules);
	}

	@Test
	void returnsEmptyAfterExhaustingTheDefaultOrder() {
		assertThat(service.estimateWithDefaultStorage(CATALOG_ID, DATE)).isEmpty();
		var order = inOrder(rules);
		order.verify(rules).findApplicableRules(CATALOG_ID, COLD, DATE);
		order.verify(rules).findApplicableRules(CATALOG_ID, ROOM, DATE);
		order.verify(rules).findApplicableRules(CATALOG_ID, FROZEN, DATE);
		verifyNoMoreInteractions(rules);
	}

	@Test
	void passesThePurchaseDateOnBothEffectiveDateBoundaries() {
		LocalDate from = DATE.minusDays(2);
		LocalDate to = DATE.plusDays(2);
		ExpiryEstimationRule applicable = rule(7);
		when(rules.findApplicableRules(CATALOG_ID, COLD, from)).thenReturn(List.of(applicable));
		when(rules.findApplicableRules(CATALOG_ID, COLD, to)).thenReturn(List.of(applicable));

		assertThat(service.estimateExpiresOn(CATALOG_ID, COLD, from)).contains(from.plusDays(7));
		assertThat(service.estimateExpiresOn(CATALOG_ID, COLD, to)).contains(to.plusDays(7));
		assertThat(service.estimateExpiresOn(CATALOG_ID, COLD, to.plusDays(1))).isEmpty();
	}

	private ExpiryEstimationRule rule(int days) {
		var rule = mock(ExpiryEstimationRule.class);
		when(rule.getShelfLifeDays()).thenReturn(days);
		return rule;
	}
}
