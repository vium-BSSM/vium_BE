package com.vium.inventory.repository;

import com.vium.inventory.entity.ConsumptionEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsumptionEventRepository extends JpaRepository<ConsumptionEvent, Long> {

	@Query(value = """
		SELECT * FROM consumption_events
		WHERE user_id = :userId
		AND (:from IS NULL OR occurred_at >= :from)
		AND (:to IS NULL OR occurred_at <= :to)
		AND (:eventType IS NULL OR event_type = :eventType)
		ORDER BY occurred_at DESC
		""", nativeQuery = true)
	List<ConsumptionEvent> findByUserIdWithFilters(
		@Param("userId") Long userId,
		@Param("from") LocalDateTime from,
		@Param("to") LocalDateTime to,
		@Param("eventType") String eventType);
}
