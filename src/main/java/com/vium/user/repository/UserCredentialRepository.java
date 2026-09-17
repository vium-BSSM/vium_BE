package com.vium.user.repository;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserCredentialRepository {
	private final JdbcTemplate jdbcTemplate;

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
