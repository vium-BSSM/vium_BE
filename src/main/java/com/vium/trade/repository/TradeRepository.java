package com.vium.trade.repository;

import com.vium.trade.entity.Trade;
import com.vium.trade.dto.TradeAccess;
import java.time.LocalDateTime;
import java.util.List;
import com.vium.trade.entity.TradeStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TradeRepository extends JpaRepository<Trade, Long> {
	@Query("select new com.vium.trade.dto.TradeAccess(t.postId, t.buyerId, t.sellerId) from Trade t where t.id = :id")
	Optional<TradeAccess> findAccessById(@Param("id") Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Trade t where t.id = :id")
	Optional<Trade> findByIdForUpdate(@Param("id") Long id);

	@Query("""
		select count(t) > 0 from Trade t
		where t.status = com.vium.trade.entity.TradeStatus.COMPLETED
		and t.id <> :excludeId and t.completedAt >= :since
		and ((t.buyerId = :a and t.sellerId = :b) or (t.buyerId = :b and t.sellerId = :a))
		""")
	boolean existsCompletedBetweenSince(@Param("a") Long a, @Param("b") Long b,
		@Param("since") LocalDateTime since, @Param("excludeId") Long excludeId);

	@Query("select t.id from Trade t where t.status = :status and t.autoResolveAt <= :now order by t.id")
	List<Long> findIdsByStatusAndAutoResolveAtLessThanEqual(@Param("status") TradeStatus status,
		@Param("now") LocalDateTime now);

	@Query("""
		select t.id from Trade t where t.status = com.vium.trade.entity.TradeStatus.ACCEPTED
		and t.autoResolveAt > :now and t.autoResolveAt <= :until
		and t.autoCompleteNotifiedAt is null order by t.id
		""")
	List<Long> findIdsToNotifySoon(@Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

	boolean existsByPostIdAndStatusIn(Long postId, Collection<TradeStatus> statuses);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Trade t where t.postId = :postId and t.status = :status")
	Optional<Trade> findByPostIdAndStatusForUpdate(@Param("postId") Long postId,
		@Param("status") TradeStatus status);
}
