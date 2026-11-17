package com.vium.report.repository;

import com.vium.report.entity.AiWasteReport;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiWasteReportRepository extends JpaRepository<AiWasteReport, Long> {

	Optional<AiWasteReport> findByUserIdAndYearMonth(Long userId, String yearMonth);

	Optional<AiWasteReport> findByIdAndUserId(Long id, Long userId);

	List<AiWasteReport> findByUserIdOrderByYearMonthDesc(Long userId);
}
