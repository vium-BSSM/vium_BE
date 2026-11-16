package com.vium.credit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "credit_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditTransaction {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 30)
	private CreditTransactionType type;

	@Column(name = "amount", nullable = false)
	private Integer amount;

	@Column(name = "trade_id")
	private Long tradeId;

	@Column(name = "reward_date")
	private LocalDate rewardDate;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public static CreditTransaction record(Long userId, CreditTransactionType type, int amount, Long tradeId,
			LocalDate rewardDate, LocalDateTime createdAt) {
		CreditTransaction transaction = new CreditTransaction();
		transaction.userId = userId;
		transaction.type = type;
		transaction.amount = amount;
		transaction.tradeId = tradeId;
		transaction.rewardDate = rewardDate;
		transaction.createdAt = createdAt;
		return transaction;
	}
}
