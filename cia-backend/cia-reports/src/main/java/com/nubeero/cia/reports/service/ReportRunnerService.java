package com.nubeero.cia.reports.service;

import com.nubeero.cia.reports.controller.dto.ReportResultDto;
import com.nubeero.cia.reports.controller.dto.ReportRunRequest;
import com.nubeero.cia.reports.domain.ReportDefinition;
import com.nubeero.cia.reports.domain.ReportField;
import com.nubeero.cia.reports.domain.ReportPin;
import com.nubeero.cia.reports.repository.ReportPinRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates: load definition → execute query → render to screen / CSV / PDF.
 */
@Service
@RequiredArgsConstructor
public class ReportRunnerService {

    private final ReportDefinitionService definitionService;
    private final ReportQueryBuilder queryBuilder;
    private final ReportCsvRenderer csvRenderer;
    private final ReportPdfRenderer pdfRenderer;
    private final ReportPinRepository pinRepository;

    @Transactional(readOnly = true)
    public ReportResultDto run(ReportRunRequest request) {
        ReportDefinition definition = definitionService.get(request.getReportId());
        List<Map<String, Object>> rows = queryBuilder.execute(definition, request.getFilters());

        List<ReportField> columns = definition.getConfig().getFields() != null
                ? definition.getConfig().getFields()
                : List.of();

        return ReportResultDto.builder()
                .columns(columns)
                .rows(rows)
                .totalRows(rows.size())
                .build();
    }

    @Transactional(readOnly = true)
    public StreamingResponseBody runCsv(ReportRunRequest request) {
        ReportDefinition definition = definitionService.get(request.getReportId());
        List<Map<String, Object>> rows = queryBuilder.execute(definition, request.getFilters());
        List<ReportField> columns = definition.getConfig().getFields() != null
                ? definition.getConfig().getFields()
                : List.of();
        return csvRenderer.render(columns, rows);
    }

    @Transactional(readOnly = true)
    public byte[] runPdf(ReportRunRequest request) {
        ReportDefinition definition = definitionService.get(request.getReportId());
        List<Map<String, Object>> rows = queryBuilder.execute(definition, request.getFilters());
        List<ReportField> columns = definition.getConfig().getFields() != null
                ? definition.getConfig().getFields()
                : List.of();
        return pdfRenderer.render(definition, columns, rows, request.getFilters());
    }

    // ── Pin management ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ReportDefinition> listPinned(String userId) {
        return pinRepository.findByUserIdOrderByDisplayOrderAsc(userId)
                .stream()
                .map(ReportPin::getReport)
                .collect(Collectors.toList());
    }

    @Transactional
    public void pin(String userId, UUID reportId) {
        if (pinRepository.existsByUserIdAndReportId(userId, reportId)) return;
        ReportDefinition report = definitionService.get(reportId);
        if (!report.isPinnable()) throw new IllegalStateException("This report cannot be pinned");

        int nextOrder = pinRepository.findByUserIdOrderByDisplayOrderAsc(userId).size();
        ReportPin pin = ReportPin.builder()
                .userId(userId)
                .report(report)
                .displayOrder(nextOrder)
                .build();
        pinRepository.save(pin);
    }

    @Transactional
    public void unpin(String userId, UUID reportId) {
        if (!pinRepository.existsByUserIdAndReportId(userId, reportId)) {
            throw new EntityNotFoundException("Pin not found for report " + reportId);
        }
        pinRepository.deleteByUserIdAndReportId(userId, reportId);
    }
}
