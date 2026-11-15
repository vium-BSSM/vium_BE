package com.vium.notification.repository;

import com.vium.notification.entity.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	Optional<Notification> findByIdAndUserId(Long id, Long userId);

	List<Notification> findAllByUserIdOrderByScheduledAtDescIdDesc(Long userId);

	List<Notification> findAllByUserIdAndReadAtIsNotNullOrderByScheduledAtDescIdDesc(Long userId);

	List<Notification> findAllByUserIdAndReadAtIsNullOrderByScheduledAtDescIdDesc(Long userId);
}
