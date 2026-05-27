package com.nubeero.cia.finance.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Append + query for {@link PdfDownloadLog}.
 *
 * <p>{@link #log} uses {@code REQUIRES_NEW} propagation so a failure to
 * write the audit row (DB issue) cannot roll back the calling download
 * transaction. Mirrors {@code AuditService.log} semantics.
 *
 * @since F11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfDownloadLogService {

    private final PdfDownloadLogRepository repository;

    /**
     * Append a download event. Best-effort — exceptions are caught,
     * logged at WARN, and swallowed so the caller's download response
     * is never blocked by an audit-row write failure.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(PdfDocumentType type, UUID entityId, String reference,
                    UUID parentId, String parentRef, String recipientName) {
        try {
            PdfDownloadLog row = PdfDownloadLog.builder()
                    .userId(currentUser())
                    .entityType(type)
                    .entityId(entityId)
                    .reference(reference)
                    .parentId(parentId)
                    .parentRef(parentRef)
                    .recipientName(recipientName)
                    .downloadedAt(Instant.now())
                    .build();
            repository.save(row);
        } catch (Exception e) {
            log.warn("Failed to write pdf_download_log row for {} {}: {}",
                     type, entityId, e.getMessage());
        }
    }

    /**
     * List the calling user's downloads from the last {@code days} days,
     * newest first, capped at {@code limit} rows.
     */
    @Transactional(readOnly = true)
    public List<PdfDownloadLogResponse> listForUser(int days, int limit) {
        Instant from = Instant.now().minus(days, ChronoUnit.DAYS);
        return repository.findByUserIdAndDownloadedAtAfterOrderByDownloadedAtDesc(
                        currentUser(), from, PageRequest.of(0, limit))
                .stream()
                .map(PdfDownloadLogResponse::from)
                .toList();
    }

    private static String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
