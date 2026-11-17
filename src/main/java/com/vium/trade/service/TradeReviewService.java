package com.vium.trade.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.trade.dto.ReviewCreateRequest;
import com.vium.trade.dto.ReviewCreateResponse;
import com.vium.trade.dto.UserReviewsResponse;
import com.vium.trade.entity.TradeReview;
import com.vium.trade.entity.TradeStatus;
import com.vium.trade.repository.TradeRepository;
import com.vium.trade.repository.TradeReviewRepository;
import com.vium.trade.repository.TradeQueryRepository;
import com.vium.user.service.UserProfileService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TradeReviewService {
	private final TradeRepository tradeRepository;
	private final TradeReviewRepository reviewRepository;
	private final TradeQueryRepository queryRepository;
	private final UserProfileService userProfileService;
	private final Clock clock;

	@Transactional
	public ReviewCreateResponse create(Long writerId, Long tradeId, ReviewCreateRequest request) {
		var trade = tradeRepository.findById(tradeId)
			.filter(t -> t.isParticipant(writerId)).orElseThrow(() -> new BusinessException(ErrorCode.TRADE_NOT_FOUND));
		trade.requireStatus(TradeStatus.COMPLETED);
		if (reviewRepository.existsByTradeIdAndWriterId(tradeId, writerId)) {
			throw new BusinessException(ErrorCode.REVIEW_ALREADY_WRITTEN);
		}
		try {
			var review = reviewRepository.saveAndFlush(TradeReview.create(tradeId, writerId, trade.partnerOf(writerId),
				request.mannerScore(), request.content(), LocalDateTime.now(clock)));
			return new ReviewCreateResponse(review.getId(), tradeId, review.getMannerScore());
		} catch (DataIntegrityViolationException duplicate) {
			throw new BusinessException(ErrorCode.REVIEW_ALREADY_WRITTEN);
		}
	}

	@Transactional(readOnly = true)
	public UserReviewsResponse list(Long userId, String cursor, int size) {
		TradeQueryService.validateSize(size);
		Long id = null;
		if (cursor != null) {
			try {
				id = Long.valueOf(cursor);
				if (id <= 0) throw new IllegalArgumentException();
			} catch (IllegalArgumentException e) { throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor 값이 올바르지 않습니다"); }
		}
		userProfileService.requireActiveUser(userId);
		var stats = queryRepository.findReviewStats(userId);
		var rows = queryRepository.findReviews(userId, id, size + 1);
		boolean hasNext = rows.size() > size;
		var page = hasNext ? rows.subList(0, size) : rows;
		return new UserReviewsResponse(userId, stats.mannerScore(), stats.reviewCount(), page,
			hasNext ? page.getLast().reviewId().toString() : null, hasNext);
	}
}
