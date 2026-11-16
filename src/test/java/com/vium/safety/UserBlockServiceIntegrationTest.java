package com.vium.safety;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.safety.service.UserBlockService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:user-block-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Sql(scripts = "/db/auth-users.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class UserBlockServiceIntegrationTest {

	private static final Long USER_A = 1L;
	private static final Long USER_B = 2L;
	private static final Long USER_C = 3L;

	@Autowired
	private UserBlockService userBlockService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from user_blocks");
	}

	private void insertBlock(Long blockerId, Long blockedId) {
		jdbcTemplate.update(
			"insert into user_blocks (blocker_id, blocked_id, created_at) values (?, ?, CURRENT_TIMESTAMP)",
			blockerId, blockedId);
	}

	@Test
	void isBlockedBetweenIsTrueRegardlessOfDirection() {
		insertBlock(USER_A, USER_B);

		assertThat(userBlockService.isBlockedBetween(USER_A, USER_B)).isTrue();
		assertThat(userBlockService.isBlockedBetween(USER_B, USER_A)).isTrue();
	}

	@Test
	void isBlockedBetweenIsFalseWithoutAnyBlock() {
		assertThat(userBlockService.isBlockedBetween(USER_A, USER_B)).isFalse();
	}

	@Test
	void isBlockedBetweenIsFalseForUnrelatedUsers() {
		insertBlock(USER_A, USER_B);

		assertThat(userBlockService.isBlockedBetween(USER_A, USER_C)).isFalse();
	}

	@Test
	void findRelatedUserIdsIncludesBothBlockerAndBlockedSides() {
		insertBlock(USER_A, USER_B);
		insertBlock(USER_C, USER_A);

		assertThat(userBlockService.findRelatedUserIds(USER_A)).isEqualTo(Set.of(USER_B, USER_C));
	}

	@Test
	void findRelatedUserIdsIsEmptyWithoutAnyBlock() {
		assertThat(userBlockService.findRelatedUserIds(USER_A)).isEmpty();
	}
}
