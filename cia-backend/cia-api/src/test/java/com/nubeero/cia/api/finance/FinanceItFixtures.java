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
 * @since Slice α — F7 Receipt/Payment visibility
 */
@Component
public class FinanceItFixtures {

    private final JdbcTemplate jdbc;

    public FinanceItFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates a debit note in OUTSTANDING status with a total of ₦200,000.00.
     * No policy/customer/product rows are required — the debit_notes table only
     * FK-references itself and finance_counters; all other references (customer_id,
     * entity_id, entity_reference) are stored as plain UUID/VARCHAR columns.
     *
     * @return the UUID of the newly created debit note
     */
    public UUID createApprovedPolicyAndDebitNote() {
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
     * Seeds a {@code finance_counters} row for the given type and current year,
     * at sequence 0. The counter auto-increments on first use — calling this
     * before any {@code FinanceNumberService.next*()} call prevents the
     * "INSERT … ON CONFLICT" path from being needed.
     *
     * <p>Not required by the current IT (FinanceNumberService creates the row
     * lazily via upsert), but provided for tests that want a predictable
     * sequence starting value.
     */
    public void seedCounter(String type, int year) {
        jdbc.update(
            "INSERT INTO finance_counters (counter_type, year, last_sequence) " +
            "VALUES (?, ?, 0) ON CONFLICT DO NOTHING",
            type, year);
    }
}
