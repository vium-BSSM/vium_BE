package com.vium.trade.repository;

import com.vium.trade.entity.Trade;
import com.vium.trade.entity.TradeStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TradeRepository extends JpaRepository<Trade, Long> {
	boolean existsByPostIdAndStatusIn(Long postId, Collection<TradeStatus> statuses);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Trade t where t.postId = :postId and t.status = :status")
	Optional<Trade> findByPostIdAndStatusForUpdate(@Param("postId") Long postId,
		@Param("status") TradeStatus status);
}
