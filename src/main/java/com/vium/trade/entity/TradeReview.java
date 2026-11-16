package com.vium.trade.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "trade_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TradeReview {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "trade_id", nullable = false)
	private Long tradeId;

	@Column(name = "writer_id", nullable = false)
	private Long writerId;

	@Column(name = "target_user_id", nullable = false)
	private Long targetUserId;

	@Column(name = "manner_score", nullable = false)
	private Short mannerScore;

	@Column(length = 500)
	private String content;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Builder
	public TradeReview(Long tradeId, Long writerId, Long targetUserId, Short mannerScore, String content,
			LocalDateTime createdAt) {
		this.tradeId = tradeId;
		this.writerId = writerId;
		this.targetUserId = targetUserId;
		this.mannerScore = mannerScore;
		this.content = content;
		this.createdAt = createdAt;
	}
}
