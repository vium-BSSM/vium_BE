package com.vium.credit.repository;

import com.vium.credit.entity.CreditBaseValue;
import com.vium.credit.entity.CreditBaseValueId;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditBaseValueRepository extends JpaRepository<CreditBaseValue, CreditBaseValueId> {

	Optional<CreditBaseValue> findByCategoryIdAndUnitId(Short categoryId, Short unitId);
}
