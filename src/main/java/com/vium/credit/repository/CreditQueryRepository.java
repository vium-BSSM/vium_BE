package com.vium.credit.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * trades 테이블을 읽기 전용으로 조회한다. trades JPA 엔티티가 아직 없어 credit 도메인에서 직접 조회한다.
 */
@Repository
@RequiredArgsConstructor
public class CreditQueryRepository {

	private final JdbcTemplate jdbcTemplate;

	public long sumHeldBalance(Long buyerId) {
		Long held = jdbcTemplate.queryForObject("""
			select coalesce(sum(price), 0) from trades
			where buyer_id = ? and status in ('REQUESTED', 'ACCEPTED')
			""", Long.class, buyerId);
		return held != null ? held : 0L;
	}
}
