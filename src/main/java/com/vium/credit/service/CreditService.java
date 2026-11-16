package com.vium.credit.service;

import com.vium.credit.entity.CreditTransaction;
import com.vium.credit.entity.CreditTransactionType;
import com.vium.credit.repository.CreditTransactionRepository;
import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.user.service.UserProfileService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditService {

	private static final int SAVING_BONUS_DAILY_LIMIT = 3;
	private static final int SAVING_BONUS_AMOUNT = 1;

	private final CreditTransactionRepository creditTransactionRepository;
	private final UserProfileService userProfileService;
	private final Clock clock;

	@Transactional(readOnly = true)
	public long getAvailableBalance(Long userId) {
		return creditTransactionRepository.sumAmountByUserId(userId);
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
