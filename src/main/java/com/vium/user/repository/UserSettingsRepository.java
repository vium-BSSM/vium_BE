package com.vium.user.repository;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserSettingsRepository {

	private final JdbcTemplate jdbcTemplate;

	public Optional<Integer> findExpiryAlertDays(Long userId) {
		return jdbcTemplate.query(
			"select expiry_alert_days from users where id = ? and deleted_at is null",
			(rs, rowNum) -> rs.getInt("expiry_alert_days"), userId).stream().findFirst();
	}
}
