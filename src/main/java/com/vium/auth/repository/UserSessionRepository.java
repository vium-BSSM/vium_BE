package com.vium.auth.repository;

import com.vium.auth.entity.UserSession;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<UserSession> findByRefreshTokenHash(String refreshTokenHash);
}
