package com.vium.inventory.repository;

import com.vium.inventory.entity.InventoryItem;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

	Optional<InventoryItem> findByIdAndUserId(Long id, Long userId);

	List<InventoryItem> findAllByUserIdAndStatusIdNotAndExpiresOnBefore(Long userId, Short statusId,
			LocalDate expiresOn);

	List<InventoryItem> findAllByStatusIdNotAndExpiresOnBefore(Short statusId, LocalDate expiresOn);
}
