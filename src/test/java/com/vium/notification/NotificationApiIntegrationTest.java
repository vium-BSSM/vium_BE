package com.vium.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vium.auth.service.TokenService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:notification-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc
class NotificationApiIntegrationTest {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokens;
	private String bearer;
	private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 9, 0);

	@BeforeEach
	void setup() {
		jdbc.update("delete from notifications");
		bearer = "Bearer " + tokens.issue(42L).accessToken();
	}

	@Test
	void listsOwnNotificationsOrderedByScheduledAtDesc() throws Exception {
		insert(1, 42, "expiry_popup", "오래된 알림", BASE, BASE, null);
		insert(2, 42, "expiry_popup", "최근 알림", BASE.plusDays(1), BASE.plusDays(1), null);
		insert(3, 99, "expiry_popup", "타인 알림", BASE.plusDays(2), BASE.plusDays(2), null);

		mvc.perform(get("/api/me/notifications").header("Authorization", bearer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.notifications.length()").value(2))
			.andExpect(jsonPath("$.data.notifications[0].notificationId").value(2))
			.andExpect(jsonPath("$.data.notifications[0].title").value("최근 알림"))
			.andExpect(jsonPath("$.data.notifications[1].notificationId").value(1))
			.andExpect(jsonPath("$.data.notifications[0].scheduledAt").value("2026-09-02T09:00:00"))
			.andExpect(jsonPath("$.data.notifications[0].readAt").doesNotExist());
	}

	@Test
	void filtersByIsRead() throws Exception {
		insert(1, 42, "expiry_popup", "읽음", BASE, BASE, BASE.plusHours(1));
		insert(2, 42, "expiry_popup", "안읽음", BASE.plusDays(1), BASE.plusDays(1), null);

		mvc.perform(get("/api/me/notifications").header("Authorization", bearer).param("isRead", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.notifications.length()").value(1))
			.andExpect(jsonPath("$.data.notifications[0].notificationId").value(1));

		mvc.perform(get("/api/me/notifications").header("Authorization", bearer).param("isRead", "false"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.notifications.length()").value(1))
			.andExpect(jsonPath("$.data.notifications[0].notificationId").value(2));
	}

	@Test
	void marksReadAndIsIdempotent() throws Exception {
		insert(1, 42, "expiry_popup", "알림", BASE, BASE, null);

		mvc.perform(patch("/api/me/notifications/1/read").header("Authorization", bearer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.notificationId").value(1))
			.andExpect(jsonPath("$.data.readAt").exists());
		var firstReadAt = jdbc.queryForObject("select read_at from notifications where id = 1", Timestamp.class);
		assertThat(firstReadAt).isNotNull();

		mvc.perform(patch("/api/me/notifications/1/read").header("Authorization", bearer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.notificationId").value(1));
		var secondReadAt = jdbc.queryForObject("select read_at from notifications where id = 1", Timestamp.class);
		assertThat(secondReadAt).isEqualTo(firstReadAt);
	}

	@Test
	void rejectsOtherUsersAndMissingNotifications() throws Exception {
		insert(1, 99, "expiry_popup", "타인 알림", BASE, BASE, null);

		mvc.perform(patch("/api/me/notifications/1/read").header("Authorization", bearer))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
		mvc.perform(patch("/api/me/notifications/999999/read").header("Authorization", bearer))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void rejectsInvalidIsReadValue() throws Exception {
		mvc.perform(get("/api/me/notifications").header("Authorization", bearer).param("isRead", "notaboolean"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void allEndpointsRequireAuthentication() throws Exception {
		mvc.perform(get("/api/me/notifications")).andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/me/notifications/1/read")).andExpect(status().isUnauthorized());
	}

	private void insert(long id, long userId, String type, String title, LocalDateTime scheduledAt,
			LocalDateTime sentAt, LocalDateTime readAt) {
		jdbc.update("insert into notifications (id,user_id,type,title,scheduled_at,sent_at,read_at,created_at) " +
				"values (?,?,?,?,?,?,?,?)",
			id, userId, type, title, Timestamp.valueOf(scheduledAt), sentAt == null ? null : Timestamp.valueOf(sentAt),
			readAt == null ? null : Timestamp.valueOf(readAt), Timestamp.valueOf(BASE));
	}
}
