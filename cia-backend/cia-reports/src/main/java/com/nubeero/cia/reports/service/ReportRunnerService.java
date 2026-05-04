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

    /**
     * Result of an export render. {@code truncated} is true when the underlying
     * dataset contained more rows than {@link ReportQueryBuilder#EXPORT_MAX_ROWS} —
     * the caller (controller) should surface this to the client so the user
     * isn't silently shown a partial export.
     */
    public record CsvExport(StreamingResponseBody body, boolean truncated, int rowsRendered) {}

    public record PdfExport(byte[] bytes, boolean truncated, int rowsRendered) {}

    @Transactional(readOnly = true)
    public CsvExport runCsv(ReportRunRequest request) {
        ReportDefinition definition = definitionService.get(request.getReportId());
        // Fetch one extra row so we can detect when the dataset exceeded the cap.
        List<Map<String, Object>> rows = queryBuilder.execute(
                definition, request.getFilters(), ReportQueryBuilder.EXPORT_MAX_ROWS + 1);
        boolean truncated = rows.size() > ReportQueryBuilder.EXPORT_MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, ReportQueryBuilder.EXPORT_MAX_ROWS);
        }
        List<ReportField> columns = definition.getConfig().getFields() != null
                ? definition.getConfig().getFields()
                : List.of();
        return new CsvExport(csvRenderer.render(columns, rows), truncated, rows.size());
    }

    @Transactional(readOnly = true)
    public PdfExport runPdf(ReportRunRequest request) {
        ReportDefinition definition = definitionService.get(request.getReportId());
        List<Map<String, Object>> rows = queryBuilder.execute(
                definition, request.getFilters(), ReportQueryBuilder.EXPORT_MAX_ROWS + 1);
        boolean truncated = rows.size() > ReportQueryBuilder.EXPORT_MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, ReportQueryBuilder.EXPORT_MAX_ROWS);
        }
        List<ReportField> columns = definition.getConfig().getFields() != null
                ? definition.getConfig().getFields()
                : List.of();
        byte[] bytes = pdfRenderer.render(definition, columns, rows, request.getFilters());
        return new PdfExport(bytes, truncated, rows.size());
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
