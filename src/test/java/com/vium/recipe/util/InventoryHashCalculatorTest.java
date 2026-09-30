package com.vium.recipe.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryHashCalculatorTest {

	@Test
	void testSameIngredientsProduceSameHash() {
		List<Long> ids1 = Arrays.asList(3L, 5L, 7L, 11L);
		List<Long> ids2 = Arrays.asList(3L, 5L, 7L, 11L);

		String hash1 = InventoryHashCalculator.calculateHash(ids1);
		String hash2 = InventoryHashCalculator.calculateHash(ids2);

		assertEquals(hash1, hash2);
		assertEquals(64, hash1.length());
	}

	@Test
	void testDifferentIngredientsProduceDifferentHash() {
		List<Long> ids1 = Arrays.asList(3L, 5L, 7L);
		List<Long> ids2 = Arrays.asList(3L, 5L, 7L, 11L);

		String hash1 = InventoryHashCalculator.calculateHash(ids1);
		String hash2 = InventoryHashCalculator.calculateHash(ids2);

		assertNotEquals(hash1, hash2);
	}

	@Test
	void testOrderDoesNotMatter() {
		List<Long> ids1 = Arrays.asList(3L, 5L, 7L);
		List<Long> ids2 = Arrays.asList(7L, 5L, 3L);

		String hash1 = InventoryHashCalculator.calculateHash(ids1);
		String hash2 = InventoryHashCalculator.calculateHash(ids2);

		assertEquals(hash1, hash2);
	}

	@Test
	void testHashIsHexadecimal() {
		List<Long> ids = Arrays.asList(1L, 2L, 3L);
		String hash = InventoryHashCalculator.calculateHash(ids);

		assertTrue(hash.matches("[0-9a-f]{64}"));
	}

	@Test
	void testDuplicateIdsProduceSameHashAsWithoutDuplicates() {
		List<Long> ids1 = Arrays.asList(1L, 2L, 3L);
		List<Long> ids2 = Arrays.asList(1L, 2L, 3L, 2L, 3L);

		String hash1 = InventoryHashCalculator.calculateHash(ids1);
		String hash2 = InventoryHashCalculator.calculateHash(ids2);

		assertEquals(hash1, hash2);
	}
}
