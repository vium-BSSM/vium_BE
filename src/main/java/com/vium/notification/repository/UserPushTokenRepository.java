package com.vium.notification.repository;

import com.vium.notification.entity.UserPushToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPushTokenRepository extends JpaRepository<UserPushToken, Long> {

	Optional<UserPushToken> findByToken(String token);

	List<UserPushToken> findAllByUserId(Long userId);

	long deleteByUserIdAndToken(Long userId, String token);

	long deleteAllByUserId(Long userId);

	long deleteByToken(String token);
}
