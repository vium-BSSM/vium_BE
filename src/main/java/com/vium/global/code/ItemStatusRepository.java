package com.vium.global.code;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemStatusRepository extends JpaRepository<ItemStatus, Short> {

	Optional<ItemStatus> findByCode(String code);
}
