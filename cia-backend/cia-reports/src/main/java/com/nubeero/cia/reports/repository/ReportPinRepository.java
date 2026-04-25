package com.nubeero.cia.reports.repository;

import com.nubeero.cia.reports.domain.ReportPin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportPinRepository extends JpaRepository<ReportPin, UUID> {

    List<ReportPin> findByUserIdOrderByDisplayOrderAsc(String userId);

    Optional<ReportPin> findByUserIdAndReportId(String userId, UUID reportId);

    boolean existsByUserIdAndReportId(String userId, UUID reportId);

    void deleteByUserIdAndReportId(String userId, UUID reportId);
}
