package com.vium.credit.entity;

import java.io.Serializable;
import java.util.Objects;

public class CreditBaseValueId implements Serializable {

	private Short categoryId;
	private Short unitId;

	public CreditBaseValueId() {
	}

	public CreditBaseValueId(Short categoryId, Short unitId) {
		this.categoryId = categoryId;
		this.unitId = unitId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof CreditBaseValueId that)) {
			return false;
		}
		return Objects.equals(categoryId, that.categoryId) && Objects.equals(unitId, that.unitId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(categoryId, unitId);
	}
}
