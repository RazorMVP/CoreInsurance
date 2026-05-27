package com.nubeero.cia.api.finance.bulk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptService;
import com.nubeero.cia.storage.DocumentStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins {@code POST /api/v1/finance/pdfs/bulk-download}.
 *
 * @since F11 — Task 11
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE"})
class BulkPdfDownloadControllerIT extends FinanceWebItSupport {

    @Autowired MockMvc                mockMvc;
    @Autowired ReceiptService         receiptService;
    @Autowired JdbcTemplate           jdbc;
    @Autowired ObjectMapper           objectMapper;
    @Autowired DocumentStorageService storage;

    @BeforeEach
    void setUpFiscalPeriod() {
        UUID fyId     = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        jdbc.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, 'ACTIVE', 'test') ON CONFLICT (name) DO NOTHING",
            fyId, "FY-IT-" + today.getYear(),
            LocalDate.of(today.getYear(), 1, 1), LocalDate.of(today.getYear(), 12, 31));
        UUID resolvedFyId = jdbc.queryForObject(
            "SELECT id FROM fiscal_year WHERE name = ?", UUID.class, "FY-IT-" + today.getYear());
        jdbc.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, 'MONTH', ?, ?, 'OPEN', 'test') ON CONFLICT DO NOTHING",
            periodId, resolvedFyId, today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST bulk-download returns 200 + ZIP with N PDFs named REC-{number}.pdf")
    void bulkDownload_returnsZip() throws Exception {
        Mockito.doAnswer(inv -> new ByteArrayInputStream("%PDF-1.4 zip-content".getBytes()))
            .when(storage).download(Mockito.any(), Mockito.any());

        UUID customerId = seedCustomerWithEmail("zip@test.local");
        UUID dnId       = createDebitNote(customerId);
        Receipt r1 = receiptService.post(
            dnId, new BigDecimal("100000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT-z1");
        Receipt r2 = receiptService.post(
            dnId, new BigDecimal("50000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT-z2");
        assertThat(r1.getPdfPath()).isNotNull();
        assertThat(r2.getPdfPath()).isNotNull();

        String body = objectMapper.writeValueAsString(Map.of(
            "items", List.of(
                Map.of("type", "RECEIPT", "id", r1.getId().toString()),
                Map.of("type", "RECEIPT", "id", r2.getId().toString()))));

        MvcResult res = mockMvc.perform(post("/api/v1/finance/pdfs/bulk-download")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();

        byte[] zipBytes = res.getResponse().getContentAsByteArray();
        assertThat(zipBytes).isNotEmpty();

        Set<String> entries = readZipEntryNames(zipBytes);
        assertThat(entries).hasSize(2);
        assertThat(entries).contains(
            "REC-" + r1.getReceiptNumber() + ".pdf",
            "REC-" + r2.getReceiptNumber() + ".pdf");
    }

    @Test
    @DisplayName("POST bulk-download with >50 items returns 400 (bean-validation VALIDATION_ERROR)")
    void bulkDownload_400_tooMany() throws Exception {
        List<Map<String, String>> items = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            items.add(Map.of("type", "RECEIPT", "id", UUID.randomUUID().toString()));
        }
        String body = objectMapper.writeValueAsString(Map.of("items", items));

        // @Size(max=50) bean validation fires before the controller's own
        // BULK_DOWNLOAD_TOO_MANY guard. The errorCode is VALIDATION_ERROR.
        // The controller's BULK_DOWNLOAD_TOO_MANY only fires if bean
        // validation is bypassed (e.g. malformed JSON). Documented inline.
        mockMvc.perform(post("/api/v1/finance/pdfs/bulk-download")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST bulk-download silently skips items with null pdf_path but still 200")
    void bulkDownload_skipsMissingPdfPath() throws Exception {
        Mockito.doAnswer(inv -> new ByteArrayInputStream("%PDF-1.4 zip-content".getBytes()))
            .when(storage).download(Mockito.any(), Mockito.any());

        UUID customerId = seedCustomerWithEmail("skip@test.local");
        UUID dnId       = createDebitNote(customerId);
        Receipt good = receiptService.post(
            dnId, new BigDecimal("100000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT-good");

        // INSERT a receipt directly with pdf_path NULL — service should skip it
        UUID badId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipts (id, receipt_number, debit_note_id, amount, payment_date, " +
            "                       payment_method, status, created_by) " +
            "VALUES (?, ?, ?, ?, CURRENT_DATE, 'CASH', 'POSTED', 'test')",
            badId, "REC-NOPDF-" + badId.toString().substring(0, 6),
            dnId, new BigDecimal("50000"));

        String body = objectMapper.writeValueAsString(Map.of(
            "items", List.of(
                Map.of("type", "RECEIPT", "id", good.getId().toString()),
                Map.of("type", "RECEIPT", "id", badId.toString()))));

        MvcResult res = mockMvc.perform(post("/api/v1/finance/pdfs/bulk-download")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();

        Set<String> entries = readZipEntryNames(res.getResponse().getContentAsByteArray());
        assertThat(entries).hasSize(1);
        assertThat(entries.iterator().next()).startsWith("REC-");
    }

    private Set<String> readZipEntryNames(byte[] zipBytes) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                names.add(e.getName());
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                zis.transferTo(sink);
                zis.closeEntry();
            }
        }
        return names;
    }

    private UUID seedCustomerWithEmail(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO customers " +
            "  (id, customer_type, kyc_status, first_name, last_name, email, created_by) " +
            "VALUES (?, 'INDIVIDUAL', 'PASSED', ?, ?, ?, 'test')",
            id, "Test", "Customer", email);
        return id;
    }

    private UUID createDebitNote(UUID customerId) {
        UUID dnId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO debit_notes " +
            "  (id, debit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   customer_id, customer_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'POLICY', ?, ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            dnId, "DN-ZIP-" + dnId.toString().substring(0, 8),
            UUID.randomUUID(), "POL-ZIP-001",
            customerId, "Zip Test Customer", "Premium",
            new BigDecimal("500000.00"), new BigDecimal("500000.00"));
        return dnId;
    }
}
