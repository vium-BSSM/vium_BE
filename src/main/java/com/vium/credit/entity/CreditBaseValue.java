package com.vium.credit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "credit_base_values")
@IdClass(CreditBaseValueId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditBaseValue {

	@Id
	@Column(name = "category_id")
	private Short categoryId;

	@Id
	@Column(name = "unit_id")
	private Short unitId;

	@Column(name = "base_value", nullable = false)
	private BigDecimal baseValue;
}
