package com.vium.trade.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "trade_reviews", uniqueConstraints = @UniqueConstraint(columnNames = {"trade_id", "writer_id"}))
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

	public static TradeReview create(Long tradeId, Long writerId, Long targetUserId, int mannerScore,
			String content, LocalDateTime now) {
		TradeReview review = new TradeReview();
		review.tradeId = tradeId;
		review.writerId = writerId;
		review.targetUserId = targetUserId;
		review.mannerScore = (short) mannerScore;
		review.content = content == null || content.isBlank() ? null : content;
		review.createdAt = now;
		return review;
	}
}
