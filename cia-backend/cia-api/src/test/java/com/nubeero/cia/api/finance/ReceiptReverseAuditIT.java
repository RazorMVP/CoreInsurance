package com.nubeero.cia.api.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditLogRepository;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.finance.DebitNoteService;
import com.nubeero.cia.finance.FinanceNumberService;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Integration test — pins the audit-log contract for
 * {@link ReceiptService#reverse(UUID, String)}.
 *
 * <p>Before Slice α / Task 1, {@code reverse()} mutated the receipt's
 * status/reversedAt/reversedBy/reversalReason columns but wrote no
 * {@code audit_log} row. The F7 visibility UI surfaces reversal audit data,
 * so the row must exist. This IT fails against the pre-fix code and passes
 * after the {@link AuditService#log} call is added.
 *
 * <p>Pattern mirrors {@link com.nubeero.cia.api.finance.gl.PeriodLockInterceptorIT}:
 * {@code @DataJpaTest} + Testcontainers Postgres via {@link FinanceItSupport},
 * services imported explicitly, fixtures via {@link FinanceItFixtures}.
 *
 * @since Slice α — F7 Receipt/Payment visibility, Task 1
 */
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    DebitNoteService.class,
    FinanceNumberService.class,
    ReceiptService.class,
    FinanceItFixtures.class,
    ReceiptReverseAuditIT.TestSupportConfig.class
})
class ReceiptReverseAuditIT extends FinanceItSupport {

    @Autowired ReceiptService receiptService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired FinanceItFixtures fixtures;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void authenticateUser() {
        // ReceiptService.currentUser() reads SecurityContextHolder.
        // WithMockUser works for @SpringBootTest security; for @DataJpaTest
        // we set the context directly — same result, zero Spring Security
        // filter-chain wiring needed.
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "alice", "pw",
                List.of(new SimpleGrantedAuthority("FINANCE_CREATE"),
                        new SimpleGrantedAuthority("FINANCE_UPDATE"))));
    }

    @Test
    @DisplayName("reverse() writes exactly one AuditLog row with action=REVERSE, " +
                 "entity_type=Receipt, old status=POSTED, new status=REVERSED")
    void reverse_writesAuditLogRowWithActionReverse() {
        UUID dnId = fixtures.createOutstandingDebitNote();
        Receipt posted = receiptService.post(
                dnId,
                new BigDecimal("100000.00"),
                LocalDate.now(),
                PaymentMethod.BANK_TRANSFER,
                null, "First Bank", null, "Initial premium"
        );

        // baseline: post() does not write an audit row; only reverse() does.
        // If post() ever gains its own audit call, the +1 delta below silently breaks.
        long auditBefore = auditLogRepository.count();

        receiptService.reverse(posted.getId(), "Customer requested refund — wrong account credited");

        long auditAfter = auditLogRepository.count();
        assertThat(auditAfter)
            .as("reverse() must write exactly one audit_log row")
            .isEqualTo(auditBefore + 1);

        var newest = auditLogRepository.findTopByOrderByTimestampDesc().orElseThrow();
        assertThat(newest.getAction())
            .as("audit row action must be REVERSE")
            .isEqualTo(AuditAction.REVERSE);
        assertThat(newest.getEntityType())
            .as("audit row entity_type must be Receipt")
            .isEqualTo("Receipt");
        assertThat(newest.getEntityId())
            .as("audit row entity_id must match the reversed receipt's id")
            .isEqualTo(posted.getId().toString());
        assertThat(newest.getOldValue())
            .as("old_value JSON must capture POSTED status before mutation")
            .contains("\"status\"")
            .contains("\"POSTED\"");
        assertThat(newest.getNewValue())
            .as("new_value JSON must capture REVERSED status after mutation")
            .contains("\"status\"")
            .contains("\"REVERSED\"");
        assertThat(newest.getNewValue())
            .as("new_value JSON must contain the reversal reason text")
            .contains("Customer requested refund");
    }

    @TestConfiguration
    static class TestSupportConfig {

        @Bean
        @Primary
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }

        @Bean
        AuditService auditService(AuditLogRepository repo, ObjectMapper mapper) {
            return new AuditService(repo, mapper, mock(ApplicationEventPublisher.class));
        }
    }
}
