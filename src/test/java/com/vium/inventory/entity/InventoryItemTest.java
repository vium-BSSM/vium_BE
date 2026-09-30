package com.vium.inventory.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InventoryItemTest {

	@Test
	void testDecreaseRemainingQuantity_ValidQuantity() {
		InventoryItem item = InventoryItem.builder()
			.userId(1L)
			.statusId((short) 1)
			.remainingQuantity(new BigDecimal("100.000"))
			.build();

		item.decreaseRemainingQuantity(new BigDecimal("30.000"));

		assertEquals(new BigDecimal("70.000"), item.getRemainingQuantity());
	}

	@Test
	void testDecreaseRemainingQuantity_ZeroQuantity_ThrowsException() {
		InventoryItem item = InventoryItem.builder()
			.userId(1L)
			.statusId((short) 1)
			.remainingQuantity(new BigDecimal("100.000"))
			.build();

		assertThrows(IllegalArgumentException.class, () ->
			item.decreaseRemainingQuantity(BigDecimal.ZERO)
		);
	}

	@Test
	void testDecreaseRemainingQuantity_NegativeQuantity_ThrowsException() {
		InventoryItem item = InventoryItem.builder()
			.userId(1L)
			.statusId((short) 1)
			.remainingQuantity(new BigDecimal("100.000"))
			.build();

		assertThrows(IllegalArgumentException.class, () ->
			item.decreaseRemainingQuantity(new BigDecimal("-10.000"))
		);
	}

	@Test
	void testDecreaseRemainingQuantity_GreaterThanOrEqualToRemaining_ThrowsException() {
		InventoryItem item = InventoryItem.builder()
			.userId(1L)
			.statusId((short) 1)
			.remainingQuantity(new BigDecimal("100.000"))
			.build();

		assertThrows(IllegalArgumentException.class, () ->
			item.decreaseRemainingQuantity(new BigDecimal("100.000"))
		);
	}

	@Test
	void testDecreaseRemainingQuantity_GreaterThanRemaining_ThrowsException() {
		InventoryItem item = InventoryItem.builder()
			.userId(1L)
			.statusId((short) 1)
			.remainingQuantity(new BigDecimal("100.000"))
			.build();

		assertThrows(IllegalArgumentException.class, () ->
			item.decreaseRemainingQuantity(new BigDecimal("150.000"))
		);
	}
}
