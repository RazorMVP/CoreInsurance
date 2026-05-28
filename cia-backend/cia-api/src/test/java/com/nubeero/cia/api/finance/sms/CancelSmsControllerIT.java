package com.nubeero.cia.api.finance.sms;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptService;
import com.nubeero.cia.finance.sms.SendReceiptSmsWorkflow;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins POST /api/v1/.../sms/cancel on the receipt side. Mirrors
 * {@code CancelEmailControllerIT} in shape (uses {@code @MockBean WorkflowClient}
 * from {@link FinanceWebItSupport}).
 *
 * <ol>
 *   <li>{@link #cancel_202_writesAuditRow} — stubs {@code workflowClient} so the
 *       typed {@code SendReceiptSmsWorkflow} stub is returned; seeds a receipt;
 *       POST → 202 + {@code cancelled=true}; verifies one CANCEL audit row.</li>
 *   <li>{@link #cancel_403_withoutFinanceUpdate} — caller has only
 *       {@code FINANCE_VIEW} → 403.</li>
 * </ol>
 *
 * @since F7-δ / R7 — Task 10.2, SMS cancel controller IT
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class CancelSmsControllerIT extends FinanceWebItSupport {

    @Autowired MockMvc        mockMvc;
    @Autowired ReceiptService receiptService;
    @Autowired JdbcTemplate   jdbc;
    @Autowired WorkflowClient workflowClient;

    @BeforeEach
    void stubWorkflowStub() {
        SendReceiptSmsWorkflow stub = mock(SendReceiptSmsWorkflow.class);
        when(workflowClient.newWorkflowStub(eq(SendReceiptSmsWorkflow.class),
                                              any(String.class)))
            .thenReturn(stub);
    }

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
    @DisplayName("POST /sms/cancel returns 202 + writes CANCEL audit row")
    void cancel_202_writesAuditRow() throws Exception {
        UUID customerId = seedCustomerWithPhone("+2348055667788");
        UUID dnId       = createDebitNote(customerId);
        Receipt r = receiptService.post(
            dnId, new BigDecimal("100000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT-sms-cc");

        mockMvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/sms/cancel",
                                dnId, r.getId()))
            .andExpect(status().isAccepted());

        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Receipt' " +
            "  AND entity_id = ? AND action = 'CANCEL'",
            Integer.class, r.getId().toString());
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"FINANCE_VIEW"})
    @DisplayName("POST /sms/cancel returns 403 without FINANCE_UPDATE")
    void cancel_403_withoutFinanceUpdate() throws Exception {
        mockMvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/sms/cancel",
                                UUID.randomUUID(), UUID.randomUUID()))
            .andExpect(status().isForbidden());
    }

    private UUID seedCustomerWithPhone(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO customers " +
            "  (id, customer_type, kyc_status, first_name, last_name, phone, created_by) " +
            "VALUES (?, 'INDIVIDUAL', 'PASSED', ?, ?, ?, 'test')",
            id, "Test", "Customer", phone);
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
            dnId, "DN-SMSCC-" + dnId.toString().substring(0, 8),
            UUID.randomUUID(), "POL-SMSCC-001",
            customerId, "SMS CC Test Customer", "Premium",
            new BigDecimal("500000.00"), new BigDecimal("500000.00"));
        return dnId;
    }
}
