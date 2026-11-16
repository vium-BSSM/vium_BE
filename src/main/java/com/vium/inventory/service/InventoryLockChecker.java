package com.vium.inventory.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;

public interface InventoryLockChecker {

	boolean isLocked(Long inventoryItemId);

	default void assertNotLocked(Long inventoryItemId) {
		if (isLocked(inventoryItemId)) {
			throw new BusinessException(ErrorCode.INVENTORY_LOCKED);
		}
	}
}
