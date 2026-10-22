package com.vium.global.code;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseSourceRepository extends JpaRepository<PurchaseSource, Short> {

	Optional<PurchaseSource> findByCode(String code);
}
