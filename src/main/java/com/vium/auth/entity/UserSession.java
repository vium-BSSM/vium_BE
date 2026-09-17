package com.vium.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSession {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "refresh_token_hash", nullable = false, unique = true, length = 255)
	private String refreshTokenHash;

	@Column(name = "issued_at", nullable = false)
	private LocalDateTime issuedAt;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "revoked_at")
	private LocalDateTime revokedAt;

	public static UserSession create(Long userId, String refreshToken, Instant issuedAt, Instant expiresAt) {
		UserSession session = new UserSession();
		session.userId = userId;
		session.refreshTokenHash = hashToken(refreshToken);
		// Existing schema uses timestamp without timezone; store session times in UTC.
		session.issuedAt = LocalDateTime.ofInstant(issuedAt, ZoneOffset.UTC);
		session.expiresAt = LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC);
		return session;
	}

	/** Session timestamps are UTC even when the server runs in another time zone. */
	public boolean isExpired(Instant now) {
		return !now.isBefore(expiresAt.toInstant(ZoneOffset.UTC));
	}

	private static String hashToken(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}
}
