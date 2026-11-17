package com.vium.trade.repository;

import com.vium.trade.entity.TradeReview;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeReviewRepository extends JpaRepository<TradeReview, Long> {
	boolean existsByTradeIdAndWriterId(Long tradeId, Long writerId);
}
