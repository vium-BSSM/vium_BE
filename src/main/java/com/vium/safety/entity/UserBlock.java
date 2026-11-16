package com.vium.safety.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_blocks")
@IdClass(UserBlockId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBlock {

	@Id
	@Column(name = "blocker_id")
	private Long blockerId;

	@Id
	@Column(name = "blocked_id")
	private Long blockedId;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public static UserBlock create(Long blockerId, Long blockedId, LocalDateTime createdAt) {
		UserBlock block = new UserBlock();
		block.blockerId = blockerId;
		block.blockedId = blockedId;
		block.createdAt = createdAt;
		return block;
	}
}
