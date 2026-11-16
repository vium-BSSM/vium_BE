package com.vium.credit.repository;

import com.vium.credit.entity.CreditTransaction;
import com.vium.credit.entity.CreditTransactionType;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, Long> {

	@Query("select coalesce(sum(t.amount), 0) from CreditTransaction t where t.userId = :userId")
	long sumAmountByUserId(@Param("userId") Long userId);

	long countByUserIdAndTypeAndRewardDate(Long userId, CreditTransactionType type, LocalDate rewardDate);

	@Query("""
		select t from CreditTransaction t
		where t.userId = :userId
		  and (:type is null or t.type = :type)
		  and (:cursor is null or t.id < :cursor)
		order by t.id desc
		""")
	List<CreditTransaction> findPage(@Param("userId") Long userId, @Param("type") CreditTransactionType type,
			@Param("cursor") Long cursor, Pageable pageable);
}
