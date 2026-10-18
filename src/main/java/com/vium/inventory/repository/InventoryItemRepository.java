package com.vium.inventory.repository;

import com.vium.inventory.entity.InventoryItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

	Optional<InventoryItem> findByIdAndUserId(Long id, Long userId);

	List<InventoryItem> findByUserIdAndIngredientCatalogId(Long userId, Long ingredientCatalogId);

	List<InventoryItem> findAllByUserIdAndStatusIdNotAndExpiresOnBefore(Long userId, Short statusId,
			LocalDate expiresOn);

	List<InventoryItem> findAllByStatusIdNotAndExpiresOnBefore(Short statusId, LocalDate expiresOn);

	List<InventoryItem> findAllByStatusIdAndExpiresOnBefore(Short statusId, LocalDate expiresOn);

	List<InventoryItem> findAllByStatusIdAndExpiresOnBeforeAndRemainingQuantityGreaterThan(
			Short statusId, LocalDate expiresOn, BigDecimal remainingQuantityThreshold);

	@Modifying(clearAutomatically = true)
	@Query("""
		UPDATE InventoryItem i
		SET i.statusId = :disposedStatusId, i.remainingQuantity = 0, i.updatedAt = :updatedAt
		WHERE i.id = :id
		  AND i.statusId = :activeStatusId
		  AND i.remainingQuantity = :expectedRemainingQuantity
		""")
	int disposeIfUnchanged(
			@Param("id") Long id,
			@Param("activeStatusId") Short activeStatusId,
			@Param("disposedStatusId") Short disposedStatusId,
			@Param("expectedRemainingQuantity") BigDecimal expectedRemainingQuantity,
			@Param("updatedAt") LocalDateTime updatedAt);
}
