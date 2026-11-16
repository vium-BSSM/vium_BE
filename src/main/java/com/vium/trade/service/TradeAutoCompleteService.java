package com.vium.trade.service;

import com.vium.trade.entity.TradeStatus;
import com.vium.trade.repository.TradeRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeAutoCompleteService {
	private final TradeRepository tradeRepository;
	private final TradeAutoCompleteProcessor processor;
	private final Clock clock;

	public int completeDueTrades() {
		return process("자동 완료", tradeRepository.findIdsByStatusAndAutoResolveAtLessThanEqual(
			TradeStatus.ACCEPTED, LocalDateTime.now(clock)), processor::complete);
	}

	public int notifySoonTrades() {
		LocalDateTime now = LocalDateTime.now(clock);
		return process("자동 완료 임박 안내", tradeRepository.findIdsToNotifySoon(now, now.plusHours(24)), processor::notifySoon);
	}

	private int process(String action, List<Long> ids, Function<Long, Boolean> process) {
		int completed = 0;
		int skipped = 0;
		int failed = 0;
		for (Long id : ids) {
			try {
				if (process.apply(id)) completed++; else skipped++;
			} catch (Exception e) {
				failed++;
				log.error("거래 {} 실패: tradeId={}", action, id, e);
			}
		}
		log.info("거래 {} 배치 완료: 처리 {}건, 스킵 {}건, 실패 {}건", action, completed, skipped, failed);
		return completed;
	}
}
