package com.nubeero.cia.reports.service;

import com.nubeero.cia.reports.domain.ReportAccessPolicy;
import com.nubeero.cia.reports.domain.ReportCategory;
import com.nubeero.cia.reports.domain.ReportDefinition;
import com.nubeero.cia.reports.repository.ReportAccessPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves effective permissions for a given (accessGroupId, report) pair.
 * Resolution order: report-level policy > category-level policy > deny.
 */
@Service
@RequiredArgsConstructor
public class ReportAccessService {

    private final ReportAccessPolicyRepository accessPolicyRepository;

    @Transactional(readOnly = true)
    public boolean canView(UUID accessGroupId, ReportDefinition report) {
        return resolve(accessGroupId, report).map(ReportAccessPolicy::isCanView).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean canExportCsv(UUID accessGroupId, ReportDefinition report) {
        return resolve(accessGroupId, report).map(ReportAccessPolicy::isCanExportCsv).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean canExportPdf(UUID accessGroupId, ReportDefinition report) {
        return resolve(accessGroupId, report).map(ReportAccessPolicy::isCanExportPdf).orElse(false);
    }

    @Transactional(readOnly = true)
    public List<ReportAccessPolicy> listByGroup(UUID accessGroupId) {
        return accessPolicyRepository.findByAccessGroupId(accessGroupId);
    }

    @Transactional
    public ReportAccessPolicy upsert(UUID accessGroupId, ReportCategory category,
                                     UUID reportId, boolean canView,
                                     boolean canExportCsv, boolean canExportPdf) {
        ReportAccessPolicy policy = resolveExact(accessGroupId, category, reportId)
                .orElseGet(() -> ReportAccessPolicy.builder()
                        .accessGroupId(accessGroupId)
                        .build());

        policy.setCategory(category);
        policy.setCanView(canView);
        policy.setCanExportCsv(canExportCsv);
        policy.setCanExportPdf(canExportPdf);
        return accessPolicyRepository.save(policy);
    }

    private Optional<ReportAccessPolicy> resolve(UUID accessGroupId, ReportDefinition report) {
        // 1. Report-level policy takes precedence
        Optional<ReportAccessPolicy> reportLevel =
                accessPolicyRepository.findByAccessGroupIdAndReport_Id(accessGroupId, report.getId());
        if (reportLevel.isPresent()) return reportLevel;

        // 2. Fall back to category-level
        return accessPolicyRepository.findByAccessGroupIdAndCategoryAndReportIsNull(
                accessGroupId, report.getCategory());
    }

    private Optional<ReportAccessPolicy> resolveExact(UUID accessGroupId,
                                                       ReportCategory category, UUID reportId) {
        if (reportId != null) {
            return accessPolicyRepository.findByAccessGroupIdAndReport_Id(accessGroupId, reportId);
        }
        return accessPolicyRepository.findByAccessGroupIdAndCategoryAndReportIsNull(
                accessGroupId, category);
    }
}
