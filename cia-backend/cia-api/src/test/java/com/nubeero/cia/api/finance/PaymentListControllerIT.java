package com.nubeero.cia.api.finance;

import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.PaymentService;
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
 * Controller-slice IT for {@code GET /api/v1/payments}.
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
 * <p>{@link #postPayment} calls the real {@link PaymentService}, so the
 * payment-number sequence, credit-note status recalculation, and audit writes
 * fire exactly as in production. Because {@link com.nubeero.cia.finance.Payment}
 * implements {@link com.nubeero.cia.common.entity.LockableByPeriod}, the
 * {@code PeriodLockInterceptor} requires an OPEN fiscal period covering today;
 * {@link #setUpFiscalPeriod} seeds one via JDBC in {@code @BeforeEach}.
 *
 * @since Slice α — Task 8, PaymentListController
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class PaymentListControllerIT extends FinanceWebItSupport {

    @Autowired MockMvc mockMvc;
    @Autowired PaymentService paymentService;
    @Autowired JdbcTemplate jdbc;

    // ------------------------------------------------------------------ setup

    /**
     * Seeds an OPEN MONTH fiscal period covering today so the
     * {@code PeriodLockInterceptor} allows Payment saves.
     *
     * <p>{@link com.nubeero.cia.finance.Payment} implements
     * {@link com.nubeero.cia.common.entity.LockableByPeriod}; without an OPEN
     * period for the booking date (= today), every {@code paymentService.post()}
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
     * Creates a bare credit-note row directly via JDBC (no FK parents needed).
     *
     * <p>Mirrors {@link FinanceItFixtures#createOutstandingCreditNote()} shape:
     * {@code entity_type='CLAIM'}, {@code entity_reference='CLM-TEST-...'},
     * {@code beneficiary_name='Test Beneficiary'}, nullable {@code beneficiary_id},
     * {@code total_amount=500000.00}. The {@code credit_notes} table stores
     * {@code entity_id} and {@code entity_reference} as plain UUID/VARCHAR
     * columns with no enforced FKs.
     *
     * @return UUID of the created credit note
     */
    private UUID createCreditNote() {
        UUID cnId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO credit_notes " +
            "  (id, credit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   beneficiary_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'CLAIM', ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            cnId,
            "CN-" + cnId.toString().substring(0, 8),
            entityId,
            "CLM-TEST-" + cnId.toString().substring(0, 4),
            "Test Beneficiary",
            "Claim settlement",
            new BigDecimal("500000.00"),
            new BigDecimal("500000.00")
        );
        return cnId;
    }

    /**
     * Posts a payment via the real {@link PaymentService}.
     *
     * <p>{@code @WithMockUser} populates {@code SecurityContextHolder} for
     * MockMvc dispatches but not for direct service calls made from the test
     * body. {@code PaymentService.currentUser()} reads
     * {@code SecurityContextHolder} — the context is already set by
     * {@code @WithMockUser}'s {@code TestSecurityContextHolderPostProcessor},
     * which runs once for the whole test and survives direct service calls.
     *
     * <p>Note: {@link PaymentService#post} has 9 parameters
     * (creditNoteId, amount, paymentDate, paymentMethod, bankId, bankName,
     * bankAccountName, bankAccountNumber, narration) — one more than
     * {@code ReceiptService.post} because outbound payments need to capture
     * the beneficiary's bank account name for audit and remittance.
     *
     * @param creditNoteId target credit note
     * @param amount       decimal string, e.g. {@code "100000.00"}
     * @return UUID of the created payment
     */
    private UUID postPayment(UUID creditNoteId, String amount) {
        return paymentService.post(
                creditNoteId,
                new BigDecimal(amount),
                LocalDate.now(),
                PaymentMethod.BANK_TRANSFER,
                /*bankId*/ null,
                "Test Bank",
                /*bankAccountName*/ "John Doe",
                /*bankAccountNumber*/ "0123456789",
                "IT fixture payment"
        ).getId();
    }

    /**
     * Reverses a posted payment via the real {@link PaymentService}.
     *
     * @param paymentId UUID of a POSTED payment
     * @param reason    reversal reason persisted on the entity
     */
    private void reversePayment(UUID paymentId, String reason) {
        paymentService.reverse(paymentId, reason);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void listPayments_returnsPagedResults() throws Exception {
        UUID cnId = createCreditNote();
        postPayment(cnId, "100000.00");
        postPayment(cnId, "200000.00");

        mockMvc.perform(get("/api/v1/payments").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()", greaterThan(1)))
                .andExpect(jsonPath("$.meta.total", greaterThan(1)));
    }

    @Test
    void listPayments_statusPostedFiltersOutReversed() throws Exception {
        UUID cnId = createCreditNote();
        UUID p1 = postPayment(cnId, "100000.00");
        UUID p2 = postPayment(cnId, "200000.00");
        reversePayment(p2, "wrong amount");

        mockMvc.perform(get("/api/v1/payments").param("status", "POSTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + p1 + "')].status").value("POSTED"))
                .andExpect(jsonPath("$.data[?(@.id == '" + p2 + "')]").isEmpty());
    }

    @Test
    void listPayments_creditNoteIdFilterNarrowsToOneCn() throws Exception {
        UUID cn1 = createCreditNote();
        UUID cn2 = createCreditNote();
        postPayment(cn1, "100000.00");
        postPayment(cn2, "300000.00");

        mockMvc.perform(get("/api/v1/payments").param("creditNoteId", cn1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.creditNoteId == '" + cn1 + "')]").exists())
                .andExpect(jsonPath("$.data[?(@.creditNoteId == '" + cn2 + "')]").isEmpty());
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"CLAIMS_VIEW"})
    void listPayments_returns403WithoutFinanceViewRole() throws Exception {
        mockMvc.perform(get("/api/v1/payments"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listPayments_paginationMetaIsPopulated() throws Exception {
        UUID cnId = createCreditNote();
        for (int i = 0; i < 25; i++) {
            postPayment(cnId, "10000.00");
        }
        mockMvc.perform(get("/api/v1/payments").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.meta.total", greaterThan(20)));
    }
}
