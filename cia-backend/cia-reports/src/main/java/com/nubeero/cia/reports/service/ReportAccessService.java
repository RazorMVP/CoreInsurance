package com.nubeero.cia.reports.service;

import com.nubeero.cia.reports.domain.ReportAccessPolicy;
import com.nubeero.cia.reports.domain.ReportCategory;
import com.nubeero.cia.reports.domain.ReportDefinition;
import com.nubeero.cia.reports.repository.ReportAccessPolicyRepository;
import com.nubeero.cia.reports.repository.ReportDefinitionRepository;
import jakarta.persistence.EntityNotFoundException;
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
    private final ReportDefinitionRepository reportDefinitionRepository;

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
        // DB constraint on report_access_policy: category IS NOT NULL OR report_id IS NOT NULL,
        // and the two are mutually exclusive (a row is either category-level or report-level,
        // never both). Validate the XOR up-front instead of letting the constraint fail later.
        if (category == null && reportId == null) {
            throw new IllegalArgumentException("Either category or reportId must be set");
        }
        if (category != null && reportId != null) {
            throw new IllegalArgumentException(
                    "category and reportId are mutually exclusive — set exactly one");
        }

        ReportAccessPolicy policy = resolveExact(accessGroupId, category, reportId)
                .orElseGet(() -> ReportAccessPolicy.builder()
                        .accessGroupId(accessGroupId)
                        .build());

        // Report-level policy XOR category-level policy — DB constraint enforces exactly one.
        if (reportId != null) {
            ReportDefinition report = reportDefinitionRepository.findById(reportId)
                    .orElseThrow(() -> new EntityNotFoundException("Report not found: " + reportId));
            policy.setReport(report);
            policy.setCategory(null);
        } else {
            policy.setReport(null);
            policy.setCategory(category);
        }
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
