package com.vium.auth.repository;

import com.vium.auth.entity.UserSession;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<UserSession> findByRefreshTokenHash(String refreshTokenHash);

	@Transactional
	@Modifying
	@Query(value = """
		delete from user_sessions
		where id in (select id from user_sessions where expires_at < :cutoff limit :batchSize)
		""", nativeQuery = true)
	int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
