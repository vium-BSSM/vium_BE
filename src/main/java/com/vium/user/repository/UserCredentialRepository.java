package com.vium.user.repository;

import com.vium.user.dto.UserIdentity;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserCredentialRepository {
	private final JdbcTemplate jdbcTemplate;

	public UserIdentity create(String email, String passwordHash, String displayName, LocalDateTime now) {
		var keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement("""
				insert into users (email, password_hash, display_name, created_at, updated_at)
				values (?, ?, ?, ?, ?)
				""", new String[] {"id"});
			statement.setString(1, email);
			statement.setString(2, passwordHash);
			statement.setString(3, displayName);
			statement.setTimestamp(4, Timestamp.valueOf(now));
			statement.setTimestamp(5, Timestamp.valueOf(now));
			return statement;
		}, keys);
		return new UserIdentity(Objects.requireNonNull(keys.getKey()).longValue(), email, displayName);
	}

	public Optional<Credentials> findActiveByEmail(String email) {
		return jdbcTemplate.query("""
			select id, email, display_name, password_hash from users
			where email = ? and deleted_at is null
			""", (rs, rowNum) -> new Credentials(rs.getLong("id"), rs.getString("email"),
				rs.getString("display_name"), rs.getString("password_hash")), email).stream().findFirst();
	}

	public record Credentials(Long id, String email, String displayName, String passwordHash) {
		@Override
		public String toString() { return "Credentials[REDACTED]"; }
	}
}
