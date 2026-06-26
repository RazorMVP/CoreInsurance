package com.nubeero.cia.api.finance;

import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.finance.CreditNoteService;
import com.nubeero.cia.finance.FinanceNumberService;
import com.nubeero.cia.finance.PolicyCommissionCreditNoteListener;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 Task 3.4 — no-CreditNote regression for the RM (relationship-manager)
 * commission path. Locks the contract that an RM-sourced
 * {@link PolicyApprovedEvent} creates NO payables {@code credit_notes} row —
 * RMs are internal staff paid via payroll, not external counterparties paid
 * out of Payables. (The GL accrual still posts Dr 5130 / Cr 2520 via
 * {@code SubledgerPostingService}; see {@code SubledgerPostingRmCommissionIT}.)
 *
 * <p>Distinct harness from {@code SubledgerPostingRmCommissionIT}: that IT
 * exercises the GL posting service in the {@code finance.gl} package; this one
 * exercises {@link PolicyCommissionCreditNoteListener} + {@link CreditNoteService},
 * so it rides {@link FinanceItSupport}'s {@code @DataJpaTest} base (which already
 * pins {@code spring.flyway.target=63}) and imports the listener + CN service +
 * number service. The listener is invoked directly (same code path Spring's
 * {@code @EventListener} would drive) and {@code credit_notes} is queried by
 * {@code entity_id}.
 *
 * <p>The BROKER control test proves the harness genuinely creates CNs, so the
 * RM COUNT=0 assertion is a real skip and not a silently-broken wiring.
 */
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    CreditNoteService.class,
    FinanceNumberService.class,
    PolicyCommissionCreditNoteListener.class
})
class PolicyCommissionCreditNoteListenerIT extends FinanceItSupport {

    @Autowired PolicyCommissionCreditNoteListener listener;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void authenticateUser() {
        // CreditNoteService.currentUser() reads SecurityContextHolder.
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "alice", "pw",
                List.of(new SimpleGrantedAuthority("FINANCE_CREATE"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private long countCreditNotesForEntity(UUID entityId) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM credit_notes WHERE entity_id = ?",
            Long.class, entityId);
        return count == null ? 0L : count;
    }

    /**
     * Arg order mirrors {@code SubledgerPostingServiceIT.policyApproved()}:
     * (policyId, policyNumber, customerId, customerName, brokerId, brokerName,
     *  productName, netPremium, currencyCode, policyEndDate, productId,
     *  classOfBusinessId, totalSumInsured, policyStartDate,
     *  commissionSourceType, commissionAmount, agentId, agentName).
     */
    @Test
    @DisplayName("RM-sourced PolicyApproved → NO credit note created (paid via payroll)")
    void rmSourcedApprovalCreatesNoCreditNote() {
        UUID policyId = UUID.randomUUID();
        listener.onPolicyApproved(new PolicyApprovedEvent(
            policyId, "POL-RM-100", UUID.randomUUID(), "Acme", null, null,
            "Motor", new BigDecimal("500000.00"), "NGN",
            LocalDate.of(2027, 5, 14), UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("10000000.00"), LocalDate.of(2026, 5, 15),
            "RELATIONSHIP_MANAGER", new BigDecimal("12500.00"), null, null,
            LocalDate.of(2026, 5, 15))); // approvalDate = start (test keeps business_date unchanged)
        entityManager.flush();

        assertThat(countCreditNotesForEntity(policyId)).isZero();
    }

    @Test
    @DisplayName("BROKER-sourced PolicyApproved → exactly one credit note (control)")
    void brokerSourcedApprovalCreatesCreditNote() {
        UUID policyId = UUID.randomUUID();
        UUID brokerId = UUID.randomUUID();
        listener.onPolicyApproved(new PolicyApprovedEvent(
            policyId, "POL-BRK-100", UUID.randomUUID(), "Acme", brokerId, "Sterling Brokers",
            "Motor", new BigDecimal("500000.00"), "NGN",
            LocalDate.of(2027, 5, 14), UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("10000000.00"), LocalDate.of(2026, 5, 15),
            "BROKER", new BigDecimal("12500.00"), null, null,
            LocalDate.of(2026, 5, 15))); // approvalDate = start (test keeps business_date unchanged)
        entityManager.flush();

        assertThat(countCreditNotesForEntity(policyId)).isEqualTo(1L);
    }
}
