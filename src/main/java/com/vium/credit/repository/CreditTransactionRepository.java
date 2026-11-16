package com.vium.credit.repository;

import com.vium.credit.entity.CreditTransaction;
import com.vium.credit.entity.CreditTransactionType;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, Long> {

	@Query("select coalesce(sum(t.amount), 0) from CreditTransaction t where t.userId = :userId")
	long sumAmountByUserId(@Param("userId") Long userId);

	long countByUserIdAndTypeAndRewardDate(Long userId, CreditTransactionType type, LocalDate rewardDate);
}
