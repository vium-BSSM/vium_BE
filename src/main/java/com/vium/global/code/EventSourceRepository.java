package com.vium.global.code;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventSourceRepository extends JpaRepository<EventSource, Short> {

	Optional<EventSource> findByCode(String code);
}
