package com.nubeero.cia.api.finance;

import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.ReceiptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-slice IT for {@code GET /api/v1/receipts}.
 *
 * <p>Extends {@link FinanceWebItSupport} ({@code @SpringBootTest +
 * @AutoConfigureMockMvc}) so the full Spring Security filter chain and
 * {@code @PreAuthorize} method security are active. External services
 * (Temporal, MinIO, bucket4j/Redis, Keycloak JWT decoder) are mocked by the
 * base class — only a Testcontainers Postgres instance is required.
 *
 * <p>Authentication uses {@code @WithMockUser} (spring-security-test), which
 * hooks into MockMvc's request-processing lifecycle via
 * {@code TestSecurityContextHolderPostProcessor}. This is the only reliable
 * way to pre-authenticate within a {@code @SpringBootTest} filter chain:
 * direct {@code SecurityContextHolder} mutation in the test body is wiped by
 * {@code SecurityContextPersistenceFilter} before {@code @PreAuthorize} is
 * reached.
 *
 * <p>{@link #postReceipt} calls the real {@link ReceiptService}, so the
 * receipt-number sequence, debit-note status recalculation, and audit writes
 * fire exactly as in production. Because {@link com.nubeero.cia.finance.Receipt}
 * implements {@link com.nubeero.cia.common.entity.LockableByPeriod}, the
 * {@code PeriodLockInterceptor} requires an OPEN fiscal period covering today;
 * {@link #setUpFiscalPeriod} seeds one via JDBC in {@code @BeforeEach}.
 *
 * @since Slice α — Task 7, ReceiptListController
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class ReceiptListControllerIT extends FinanceWebItSupport {

    @Autowired MockMvc mockMvc;
    @Autowired ReceiptService receiptService;
    @Autowired JdbcTemplate jdbc;

    // ------------------------------------------------------------------ setup

    /**
     * Seeds an OPEN MONTH fiscal period covering today so the
     * {@code PeriodLockInterceptor} allows Receipt saves.
     *
     * <p>{@link com.nubeero.cia.finance.Receipt} implements
     * {@link com.nubeero.cia.common.entity.LockableByPeriod}; without an OPEN
     * period for the booking date (= today), every {@code receiptService.post()}
     * call throws {@code PeriodLockedException}. Pattern mirrors
     * {@code PeriodLockInterceptorIT.setUp()}.
     */
    @BeforeEach
    void setUpFiscalPeriod() {
        UUID fyId     = UUID.randomUUID(); // used only if name doesn't exist yet
        UUID periodId = UUID.randomUUID(); // used only if period doesn't exist yet
        LocalDate today = LocalDate.now();
        LocalDate yearStart = LocalDate.of(today.getYear(), 1, 1);
        LocalDate yearEnd   = LocalDate.of(today.getYear(), 12, 31);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd   = today.withDayOfMonth(today.lengthOfMonth());

        // ON CONFLICT DO NOTHING makes the insert idempotent across the
        // multiple @BeforeEach invocations that run once per test method.
        jdbc.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (name) DO NOTHING",
            fyId, "FY-IT-" + today.getYear(), yearStart, yearEnd, "ACTIVE", "test");

        // Resolve the actual FY id (may differ from fyId if the row already existed).
        UUID resolvedFyId = jdbc.queryForObject(
            "SELECT id FROM fiscal_year WHERE name = ?",
            UUID.class, "FY-IT-" + today.getYear());

        jdbc.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            periodId, resolvedFyId, "MONTH", monthStart, monthEnd, "OPEN", "test");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------ fixture helpers

    /**
     * Creates a bare debit-note row directly via JDBC (no FK parents needed).
     * The {@code debit_notes} table stores customer_id, entity_id, and
     * entity_reference as plain UUID/VARCHAR columns with no enforced FKs.
     */
    private UUID createDebitNote() {
        UUID dnId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO debit_notes " +
            "  (id, debit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   customer_id, customer_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'POLICY', ?, ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            dnId,
            "DN-" + dnId.toString().substring(0, 8),
            entityId,
            "POL-TEST-" + dnId.toString().substring(0, 4),
            customerId,
            "Test Customer",
            "Premium",
            new BigDecimal("500000.00"),
            new BigDecimal("500000.00")
        );
        return dnId;
    }

    /**
     * Posts a receipt via the real {@link ReceiptService}.
     *
     * <p>{@code @WithMockUser} populates {@code SecurityContextHolder} for
     * MockMvc dispatches but not for direct service calls made from the test
     * body. {@code ReceiptService.currentUser()} reads
     * {@code SecurityContextHolder} — the context is already set by
     * {@code @WithMockUser}'s {@code TestSecurityContextHolderPostProcessor},
     * which runs once for the whole test and survives direct service calls.
     *
     * @param debitNoteId target debit note
     * @param amount      decimal string, e.g. {@code "100000.00"}
     * @return UUID of the created receipt
     */
    private UUID postReceipt(UUID debitNoteId, String amount) {
        return receiptService.post(
                debitNoteId,
                new BigDecimal(amount),
                LocalDate.now(),
                PaymentMethod.BANK_TRANSFER,
                null, "Test Bank", null, "IT fixture receipt"
        ).getId();
    }

    /**
     * Reverses a posted receipt via the real {@link ReceiptService}.
     *
     * @param receiptId UUID of a POSTED receipt
     * @param reason    reversal reason persisted on the entity
     */
    private void reverseReceipt(UUID receiptId, String reason) {
        receiptService.reverse(receiptId, reason);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void listReceipts_returnsPagedResults() throws Exception {
        UUID dnId = createDebitNote();
        postReceipt(dnId, "100000.00");
        postReceipt(dnId, "200000.00");

        mockMvc.perform(get("/api/v1/receipts").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()", greaterThan(1)))
                .andExpect(jsonPath("$.meta.total", greaterThan(1)));
    }

    @Test
    void listReceipts_statusPostedFiltersOutReversed() throws Exception {
        UUID dnId = createDebitNote();
        UUID r1 = postReceipt(dnId, "100000.00");
        UUID r2 = postReceipt(dnId, "200000.00");
        reverseReceipt(r2, "wrong amount");

        mockMvc.perform(get("/api/v1/receipts").param("status", "POSTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + r1 + "')].status").value("POSTED"))
                .andExpect(jsonPath("$.data[?(@.id == '" + r2 + "')]").isEmpty());
    }

    @Test
    void listReceipts_debitNoteIdFilterNarrowsToOneDn() throws Exception {
        UUID dn1 = createDebitNote();
        UUID dn2 = createDebitNote();
        postReceipt(dn1, "100000.00");
        postReceipt(dn2, "300000.00");

        mockMvc.perform(get("/api/v1/receipts").param("debitNoteId", dn1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.debitNoteId == '" + dn1 + "')]").exists())
                .andExpect(jsonPath("$.data[?(@.debitNoteId == '" + dn2 + "')]").isEmpty());
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"CLAIMS_VIEW"})
    void listReceipts_returns403WithoutFinanceViewRole() throws Exception {
        mockMvc.perform(get("/api/v1/receipts"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listReceipts_paginationMetaIsPopulated() throws Exception {
        UUID dnId = createDebitNote();
        for (int i = 0; i < 25; i++) {
            postReceipt(dnId, "10000.00");
        }
        mockMvc.perform(get("/api/v1/receipts").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.meta.total", greaterThan(20)));
    }
}
