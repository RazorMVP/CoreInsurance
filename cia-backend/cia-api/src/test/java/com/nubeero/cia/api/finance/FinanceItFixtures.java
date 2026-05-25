package com.nubeero.cia.api.finance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Test-only helper that seeds Finance module fixtures directly via
 * {@link JdbcTemplate}, bypassing the full policy approval workflow.
 *
 * <p>This is the correct approach for Finance IT tests — the policy/quotation
 * approval workflow involves Temporal, NAICOM stubs, and other modules that
 * are out of scope for a slice-scoped Finance IT. Direct JDBC inserts produce
 * a consistent, deterministic state that tests can reason about exactly.
 *
 * <p>Transactional helpers that require business-service logic (posting or
 * reversing receipts) are not housed here because they would require
 * {@link com.nubeero.cia.finance.ReceiptService} as a constructor dependency,
 * which breaks the existing {@code @DataJpaTest} ITs that import this class
 * without importing {@code ReceiptService} (e.g. {@code PaymentReverseAuditIT}).
 * Controller ITs that extend {@link FinanceWebItSupport} have the full
 * {@code @SpringBootTest} context and access the service beans directly via
 * {@code @Autowired} — no indirection through this class is needed.
 *
 * @since Slice α — F7 Receipt/Payment visibility
 */
@Component
public class FinanceItFixtures {

    private final JdbcTemplate jdbc;

    public FinanceItFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a single {@code debit_notes} row (status=OUTSTANDING, total=₦200,000.00)
     * via {@link JdbcTemplate} for tests that only need a DN to attach a receipt or
     * payment to. Does not create any parent entities (no policy row, no customer row).
     *
     * <p>The {@code debit_notes} table stores {@code customer_id}, {@code entity_id},
     * and {@code entity_reference} as plain UUID/VARCHAR columns, so no FK-parent rows
     * are required.
     *
     * @return the UUID of the newly created debit note
     */
    public UUID createOutstandingDebitNote() {
        UUID dnId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID(); // synthetic policy id
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
            "POL-TEST-001",
            customerId,
            "Test Customer",
            "Premium for policy POL-TEST-001",
            new BigDecimal("200000.00"),
            new BigDecimal("200000.00")
        );
        return dnId;
    }

    /**
     * Inserts a single {@code credit_notes} row (status=OUTSTANDING, total=₦250,000.00)
     * via {@link JdbcTemplate} for tests that only need a CN to attach a payment to.
     * Does not create any parent entities (no claim row, no customer row).
     *
     * <p>The {@code credit_notes} table stores {@code entity_id} and
     * {@code entity_reference} as plain UUID/VARCHAR columns, so no FK-parent rows
     * are required. The {@code beneficiary_id} column is nullable, so no beneficiary
     * row is required either.
     *
     * @return the UUID of the newly created credit note
     */
    public UUID createOutstandingCreditNote() {
        UUID cnId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID(); // synthetic claim id

        jdbc.update(
            "INSERT INTO credit_notes " +
            "  (id, credit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   beneficiary_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'CLAIM', ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            cnId,
            "CN-" + cnId.toString().substring(0, 8),
            entityId,
            "CLM-TEST-001",
            "Test Beneficiary",
            "Claim settlement for CLM-TEST-001",
            new BigDecimal("250000.00"),
            new BigDecimal("250000.00")
        );
        return cnId;
    }

}
