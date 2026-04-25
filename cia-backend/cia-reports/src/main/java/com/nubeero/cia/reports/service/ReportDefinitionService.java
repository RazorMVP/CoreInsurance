package com.nubeero.cia.reports.service;

import com.nubeero.cia.reports.controller.dto.CreateReportRequest;
import com.nubeero.cia.reports.domain.ReportCategory;
import com.nubeero.cia.reports.domain.ReportDefinition;
import com.nubeero.cia.reports.domain.ReportType;
import com.nubeero.cia.reports.repository.ReportDefinitionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportDefinitionService {

    private final ReportDefinitionRepository reportDefinitionRepository;

    @Transactional(readOnly = true)
    public List<ReportDefinition> listAll() {
        return reportDefinitionRepository.findByActiveTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<ReportDefinition> listByCategory(ReportCategory category) {
        return reportDefinitionRepository.findByCategoryAndActiveTrueOrderByNameAsc(category);
    }

    @Transactional(readOnly = true)
    public ReportDefinition get(UUID id) {
        return reportDefinitionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Report not found: " + id));
    }

    @Transactional
    public ReportDefinition create(CreateReportRequest req) {
        ReportDefinition report = ReportDefinition.builder()
                .name(req.getName())
                .description(req.getDescription())
                .category(req.getCategory())
                .type(ReportType.CUSTOM)
                .dataSource(req.getDataSource())
                .config(req.getConfig())
                .pinnable(true)
                .active(true)
                .build();
        return reportDefinitionRepository.save(report);
    }

    @Transactional
    public ReportDefinition update(UUID id, CreateReportRequest req) {
        ReportDefinition report = get(id);
        if (report.getType() == ReportType.SYSTEM) {
            throw new IllegalStateException("SYSTEM reports cannot be edited — clone first");
        }
        report.setName(req.getName());
        report.setDescription(req.getDescription());
        report.setCategory(req.getCategory());
        report.setDataSource(req.getDataSource());
        report.setConfig(req.getConfig());
        return reportDefinitionRepository.save(report);
    }

    @Transactional
    public void delete(UUID id) {
        ReportDefinition report = get(id);
        if (report.getType() == ReportType.SYSTEM) {
            throw new IllegalStateException("SYSTEM reports cannot be deleted");
        }
        report.softDelete();
        report.setActive(false);
        reportDefinitionRepository.save(report);
    }

    /** Clone a SYSTEM report into a new CUSTOM report for the requesting user. */
    @Transactional
    public ReportDefinition clone(UUID sourceId, String newName) {
        ReportDefinition source = get(sourceId);
        ReportDefinition copy = ReportDefinition.builder()
                .name(newName != null ? newName : source.getName() + " (Copy)")
                .description(source.getDescription())
                .category(source.getCategory())
                .type(ReportType.CUSTOM)
                .dataSource(source.getDataSource())
                .config(source.getConfig())
                .pinnable(true)
                .active(true)
                .build();
        return reportDefinitionRepository.save(copy);
    }
}
