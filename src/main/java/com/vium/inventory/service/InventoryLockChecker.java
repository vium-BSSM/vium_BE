package com.vium.inventory.service;

public interface InventoryLockChecker {

	void assertNotLocked(Long inventoryItemId);
}
