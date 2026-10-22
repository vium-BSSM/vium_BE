package com.vium.receipt.repository;

import com.vium.receipt.entity.PurchaseItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseItemRepository extends JpaRepository<PurchaseItem, Long> {

	List<PurchaseItem> findAllByPurchaseIdOrderByIdAsc(Long purchaseId);

	void deleteAllByPurchaseId(Long purchaseId);
}
