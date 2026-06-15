package com.nubeero.cia.compliance.dsar;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Orchestrates a DSAR export: gather footprint → render JSON+PDF → ZIP → write a metadata-only audit row. */
@Service
@RequiredArgsConstructor
public class DsarExportService {

    private final DsarGatherService gather;
    private final DsarJsonRenderer json;
    private final DsarPdfRenderer pdf;
    private final AuditService audit;

    public byte[] renderJson(UUID customerId, String requestedBy) {
        byte[] bytes = json.render(gather.gather(customerId));
        recordAudit(customerId, requestedBy, "JSON");
        return bytes;
    }

    public byte[] renderPdf(UUID customerId, String requestedBy) {
        byte[] bytes = pdf.render(gather.gather(customerId));
        recordAudit(customerId, requestedBy, "PDF");
        return bytes;
    }

    /** Default DSAR download — a ZIP of both files. Writes the audit row exactly once. */
    public byte[] exportZip(UUID customerId, String requestedBy) {
        DsarExport export = gather.gather(customerId);
        byte[] jsonBytes = json.render(export);
        byte[] pdfBytes = pdf.render(export);
        byte[] zip = zip(export.customerNumber(), jsonBytes, pdfBytes);
        recordAudit(customerId, requestedBy, "ZIP");
        return zip;
    }

    private byte[] zip(String customerNumber, byte[] jsonBytes, byte[] pdfBytes) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("dsar-" + customerNumber + ".json"));
            zos.write(jsonBytes);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("dsar-" + customerNumber + ".pdf"));
            zos.write(pdfBytes);
            zos.closeEntry();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build DSAR ZIP", e);
        }
        return baos.toByteArray();
    }

    /**
     * Metadata-only audit — records THAT a DSAR was produced (who + which format), never the
     * exported PII. Every disclosure path (JSON / PDF / ZIP) writes exactly one row, so the audit
     * trail is complete: a single-file fetch is still a full-PII disclosure and is logged.
     */
    private void recordAudit(UUID customerId, String requestedBy, String format) {
        audit.logWithReason("Customer", customerId.toString(), AuditAction.SEND,
                null, Map.of("dsarExportedBy", requestedBy == null ? "system" : requestedBy,
                        "format", format),
                "NDPR_DSAR_EXPORT");
    }
}
