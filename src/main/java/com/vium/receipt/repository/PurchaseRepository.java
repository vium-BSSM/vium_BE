package com.vium.receipt.repository;

import com.vium.receipt.entity.Purchase;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

	Optional<Purchase> findByIdAndUserId(Long id, Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Purchase p WHERE p.id = :id AND p.userId = :userId")
	Optional<Purchase> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}
