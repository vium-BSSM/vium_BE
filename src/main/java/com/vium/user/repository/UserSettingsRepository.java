package com.vium.user.repository;

import com.vium.user.dto.UserSettingsResponse;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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

	public Optional<UserSettingsResponse> findActiveSettingsById(Long userId) {
		return jdbcTemplate.query("""
			select expiry_alert_days, auto_dispose_enabled, shopping_reminder_enabled, push_enabled
			from users where id = ? and deleted_at is null
			""", (rs, rowNum) -> new UserSettingsResponse(
				rs.getInt("expiry_alert_days"),
				rs.getBoolean("auto_dispose_enabled"),
				rs.getBoolean("shopping_reminder_enabled"),
				rs.getBoolean("push_enabled")), userId).stream().findFirst();
	}

	public int updateSettings(Long userId, Integer expiryAlertDays, Boolean autoDisposeEnabled,
			Boolean shoppingReminderEnabled, Boolean pushEnabled, LocalDateTime now) {
		List<String> setClauses = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		if (expiryAlertDays != null) {
			setClauses.add("expiry_alert_days = ?");
			params.add(expiryAlertDays);
		}
		if (autoDisposeEnabled != null) {
			setClauses.add("auto_dispose_enabled = ?");
			params.add(autoDisposeEnabled);
		}
		if (shoppingReminderEnabled != null) {
			setClauses.add("shopping_reminder_enabled = ?");
			params.add(shoppingReminderEnabled);
		}
		if (pushEnabled != null) {
			setClauses.add("push_enabled = ?");
			params.add(pushEnabled);
		}
		setClauses.add("updated_at = ?");
		params.add(Timestamp.valueOf(now));
		params.add(userId);
		String sql = "update users set " + String.join(", ", setClauses) + " where id = ? and deleted_at is null";
		return jdbcTemplate.update(sql, params.toArray());
	}
}
