package com.nubeero.cia.reports.repository;

import com.nubeero.cia.reports.domain.ReportAccessPolicy;
import com.nubeero.cia.reports.domain.ReportCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportAccessPolicyRepository extends JpaRepository<ReportAccessPolicy, UUID> {

    List<ReportAccessPolicy> findByAccessGroupId(UUID accessGroupId);

    /** Category-level policy (report is null) */
    Optional<ReportAccessPolicy> findByAccessGroupIdAndCategoryAndReportIsNull(
            UUID accessGroupId, ReportCategory category);

    /** Report-level policy (category may be null) */
    Optional<ReportAccessPolicy> findByAccessGroupIdAndReport_Id(
            UUID accessGroupId, UUID reportId);

    void deleteByAccessGroupId(UUID accessGroupId);
}
