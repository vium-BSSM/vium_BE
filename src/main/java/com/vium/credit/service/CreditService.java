package com.vium.credit.service;

import com.vium.credit.dto.CreditTransactionItem;
import com.vium.credit.dto.CreditTransactionPageResponse;
import com.vium.credit.entity.CreditTransaction;
import com.vium.credit.entity.CreditTransactionType;
import com.vium.credit.repository.CreditBaseValueRepository;
import com.vium.credit.repository.CreditQueryRepository;
import com.vium.credit.repository.CreditTransactionRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.user.service.UserProfileService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditService {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final int SAVING_BONUS_DAILY_LIMIT = 3;
	private static final int SAVING_BONUS_AMOUNT = 1;
	private static final int MIN_TRANSACTION_PAGE_SIZE = 1;
	private static final int MAX_TRANSACTION_PAGE_SIZE = 50;

	private final CreditTransactionRepository creditTransactionRepository;
	private final CreditQueryRepository creditQueryRepository;
	private final CreditBaseValueRepository creditBaseValueRepository;
	private final UserProfileService userProfileService;
	private final Clock clock;

	/**
	 * post 도메인이 추천가를 계산할 때 쓰는 (카테고리, 기준 단위) 기준값. 시드가 없으면 비어 있다.
	 */
	@Transactional(readOnly = true)
	public Optional<BigDecimal> findBaseValue(Short categoryId, Short unitId) {
		return creditBaseValueRepository.findByCategoryIdAndUnitId(categoryId, unitId)
			.map(creditBaseValue -> creditBaseValue.getBaseValue());
	}

	@Transactional(readOnly = true)
	public long getAvailableBalance(Long userId) {
		return creditTransactionRepository.sumAmountByUserId(userId);
	}

	@Transactional(readOnly = true)
	public long getHeldBalance(Long userId) {
		return creditQueryRepository.sumHeldBalance(userId);
	}

	@Transactional
	public void hold(Long userId, Long tradeId, int amount) {
		requirePositive(amount);
		applyLockedChange(userId, CreditTransactionType.TRADE_HOLD, -amount, tradeId, null);
	}

	@Transactional
	public void refund(Long userId, Long tradeId, int amount) {
		requirePositive(amount);
		save(userId, CreditTransactionType.TRADE_REFUND, amount, tradeId, null);
	}

	@Transactional
	public void payIncome(Long sellerId, Long tradeId, int amount) {
		requirePositive(amount);
		save(sellerId, CreditTransactionType.TRADE_INCOME, amount, tradeId, null);
	}

	@Transactional
	public void deduct(Long userId, int amount, CreditTransactionType type) {
		if (type != CreditTransactionType.AI_REPORT) {
			throw new IllegalArgumentException("deduct는 AI_REPORT 타입만 허용합니다");
		}
		requirePositive(amount);
		applyLockedChange(userId, type, -amount, null, null);
	}

	@Transactional
	public boolean grantSavingBonus(Long sellerId, Long tradeId) {
		userProfileService.lockActiveUser(sellerId);
		LocalDate today = LocalDate.now(clock);
		long grantedToday = creditTransactionRepository
			.countByUserIdAndTypeAndRewardDate(sellerId, CreditTransactionType.SAVING_BONUS, today);
		if (grantedToday >= SAVING_BONUS_DAILY_LIMIT) {
			return false;
		}
		save(sellerId, CreditTransactionType.SAVING_BONUS, SAVING_BONUS_AMOUNT, tradeId, today);
		return true;
	}

	@Transactional(readOnly = true)
	public CreditTransactionPageResponse getTransactionPage(Long userId, CreditTransactionType type, Long cursor,
			int size) {
		if (size < MIN_TRANSACTION_PAGE_SIZE || size > MAX_TRANSACTION_PAGE_SIZE) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST,
				"size는 " + MIN_TRANSACTION_PAGE_SIZE + "~" + MAX_TRANSACTION_PAGE_SIZE + " 사이여야 합니다");
		}
		List<CreditTransaction> rows = creditTransactionRepository
			.findPage(userId, type, cursor, PageRequest.of(0, size + 1));
		boolean hasNext = rows.size() > size;
		List<CreditTransaction> page = hasNext ? rows.subList(0, size) : rows;
		List<CreditTransactionItem> items = page.stream()
			.map(t -> new CreditTransactionItem(t.getId(), t.getType(), t.getAmount(), t.getTradeId(),
				t.getCreatedAt().atZone(SEOUL).toOffsetDateTime()))
			.toList();
		Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
		return new CreditTransactionPageResponse(items, nextCursor, hasNext);
	}

	@Transactional
	public long claimDailyReward(Long userId, int amount) {
		LocalDate today = LocalDate.now(clock);
		if (creditTransactionRepository
				.countByUserIdAndTypeAndRewardDate(userId, CreditTransactionType.DAILY_REWARD, today) > 0) {
			throw new BusinessException(ErrorCode.DAILY_REWARD_ALREADY_CLAIMED);
		}
		try {
			creditTransactionRepository.saveAndFlush(CreditTransaction.record(
				userId, CreditTransactionType.DAILY_REWARD, amount, null, today, LocalDateTime.now(clock)));
		} catch (DataIntegrityViolationException claimedByConcurrentRequest) {
			throw new BusinessException(ErrorCode.DAILY_REWARD_ALREADY_CLAIMED);
		}
		return creditTransactionRepository.sumAmountByUserId(userId);
	}

	private void applyLockedChange(Long userId, CreditTransactionType type, int signedAmount, Long tradeId,
			LocalDate rewardDate) {
		userProfileService.lockActiveUser(userId);
		long balance = creditTransactionRepository.sumAmountByUserId(userId);
		if (balance + signedAmount < 0) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_CREDIT);
		}
		save(userId, type, signedAmount, tradeId, rewardDate);
	}

	private void save(Long userId, CreditTransactionType type, int amount, Long tradeId, LocalDate rewardDate) {
		LocalDateTime now = LocalDateTime.now(clock);
		creditTransactionRepository.save(CreditTransaction.record(userId, type, amount, tradeId, rewardDate, now));
	}

	private void requirePositive(int amount) {
		if (amount <= 0) {
			throw new IllegalArgumentException("amount는 양수여야 합니다");
		}
	}
}
