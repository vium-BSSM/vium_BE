package com.vium.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_push_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPushToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "token", nullable = false, unique = true, length = 512)
	private String token;

	@Enumerated(EnumType.STRING)
	@Column(name = "platform", nullable = false, length = 10)
	private PushPlatform platform;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	private UserPushToken(Long userId, String token, PushPlatform platform, LocalDateTime now) {
		this.userId = userId;
		this.token = token;
		this.platform = platform;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static UserPushToken register(Long userId, String token, PushPlatform platform, LocalDateTime now) {
		return new UserPushToken(userId, token, platform, now);
	}

	/**
	 * 같은 토큰 재등록(소유자 동일) 및 다른 사용자가 가져간 경우(소유자 이전) 모두 이 메서드로 처리한다.
	 */
	public void reassign(Long userId, PushPlatform platform, LocalDateTime now) {
		this.userId = userId;
		this.platform = platform;
		this.updatedAt = now;
	}
}
