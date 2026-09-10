package com.vium.ingredient.repository;

import com.vium.ingredient.entity.ExpiryEstimationRule;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpiryEstimationRuleRepository extends JpaRepository<ExpiryEstimationRule, Long> {

	@Query("""
		select r from ExpiryEstimationRule r
		where r.ingredientCatalogId = :ingredientCatalogId
		  and r.storageMethodId = :storageMethodId
		  and r.effectiveFrom <= :onDate
		  and (r.effectiveTo is null or r.effectiveTo >= :onDate)
		order by r.effectiveFrom desc
		""")
	List<ExpiryEstimationRule> findApplicableRules(
		@Param("ingredientCatalogId") Long ingredientCatalogId,
		@Param("storageMethodId") Short storageMethodId,
		@Param("onDate") LocalDate onDate);
}
