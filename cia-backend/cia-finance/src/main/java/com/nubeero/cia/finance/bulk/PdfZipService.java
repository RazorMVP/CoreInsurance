package com.nubeero.cia.finance.bulk;

import com.nubeero.cia.finance.Payment;
import com.nubeero.cia.finance.PaymentRepository;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptRepository;
import com.nubeero.cia.finance.audit.PdfDocumentType;
import com.nubeero.cia.finance.audit.PdfDownloadLogService;
import com.nubeero.cia.storage.DocumentStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams a ZIP of receipt + payment PDFs into a byte array. Items with
 * a null {@code pdf_path} are silently skipped (logged at WARN). For each
 * resolved item, a {@code pdf_download_log} row is written via
 * {@link PdfDownloadLogService} — so a 30-receipt bulk download appears
 * as 30 entries in the operator's RecentDownloadsPanel.
 *
 * @since F11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfZipService {

    private final ReceiptRepository       receiptRepository;
    private final PaymentRepository       paymentRepository;
    private final DocumentStorageService  storage;
    private final PdfDownloadLogService   downloadLog;

    public byte[] buildZip(String tenantId, BulkDownloadRequest request) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            for (BulkDownloadItem item : request.items()) {
                appendItem(tenantId, item, zip);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to build PDF ZIP", e);
        }
        return baos.toByteArray();
    }

    private void appendItem(String tenantId, BulkDownloadItem item, ZipOutputStream zip) {
        String pdfPath;
        String fileName;
        if (item.type() == PdfDocumentType.RECEIPT) {
            Optional<Receipt> opt = receiptRepository.findByIdAndDeletedAtIsNull(item.id());
            if (opt.isEmpty() || opt.get().getPdfPath() == null) {
                log.warn("Skipping bulk-download item RECEIPT {} — not found or pdf_path null", item.id());
                return;
            }
            Receipt r = opt.get();
            pdfPath  = r.getPdfPath();
            fileName = "REC-" + r.getReceiptNumber() + ".pdf";
            downloadLog.log(PdfDocumentType.RECEIPT, r.getId(), r.getReceiptNumber(),
                    r.getDebitNote() != null ? r.getDebitNote().getId() : null,
                    r.getDebitNote() != null ? r.getDebitNote().getDebitNoteNumber() : null,
                    r.getDebitNote() != null ? r.getDebitNote().getCustomerName() : null);
        } else {
            Optional<Payment> opt = paymentRepository.findByIdAndDeletedAtIsNull(item.id());
            if (opt.isEmpty() || opt.get().getPdfPath() == null) {
                log.warn("Skipping bulk-download item PAYMENT {} — not found or pdf_path null", item.id());
                return;
            }
            Payment p = opt.get();
            pdfPath  = p.getPdfPath();
            fileName = "PAY-" + p.getPaymentNumber() + ".pdf";
            downloadLog.log(PdfDocumentType.PAYMENT, p.getId(), p.getPaymentNumber(),
                    p.getCreditNote() != null ? p.getCreditNote().getId() : null,
                    p.getCreditNote() != null ? p.getCreditNote().getCreditNoteNumber() : null,
                    p.getCreditNote() != null ? p.getCreditNote().getBeneficiaryName() : null);
        }

        try (InputStream in = storage.download(tenantId, pdfPath)) {
            zip.putNextEntry(new ZipEntry(fileName));
            in.transferTo(zip);
            zip.closeEntry();
        } catch (IOException e) {
            log.warn("Failed to add {} to bulk ZIP: {}", fileName, e.getMessage());
        }
    }
}
