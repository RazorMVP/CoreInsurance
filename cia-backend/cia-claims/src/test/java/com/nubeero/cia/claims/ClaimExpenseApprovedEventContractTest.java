package com.nubeero.cia.claims;

import com.nubeero.cia.common.event.ClaimExpenseApprovedEvent;
import com.nubeero.cia.common.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slice T1 — upstream contract test for {@link ClaimExpenseApprovedEvent}.
 *
 * <p>Guards the contract that Module 12's {@code SubledgerPostingService}
 * consumes when a claim-related expense (surveyor fee, assessor fee, legal,
 * etc.) is approved — drives the expense JE (Dr 5xxx expense / Cr 2xxx
 * payable to vendor).
 *
 * <p>Two payload contracts worth a dedicated test:
 * <ol>
 *   <li>{@code expenseReference} is a derived string assembled by the
 *       producer as {@code claimNumber + "-EXP-" + first 8 chars of UUID}.
 *       SubledgerPostingService threads this through to the JE narrative
 *       and the idempotency triple, so the derivation MUST stay stable.</li>
 *   <li>{@code currencyCode} on the event is read from the parent
 *       {@code Claim}, not from the {@code ClaimExpense} itself (expenses
 *       have no currency column). A refactor that adds a currency field to
 *       ClaimExpense and forgets to update the publisher would silently
 *       break this. The test fails first if that drift happens.</li>
 * </ol>
 *
 * <p>Pure Mockito unit test, no Spring context, no DB.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClaimExpenseApprovedEventContractTest {

    @Mock private ClaimExpenseRepository expenseRepository;
    @Mock private ClaimRepository claimRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ClaimExpenseService service;

    @BeforeEach
    void setUp() {
        service = new ClaimExpenseService(expenseRepository, claimRepository, eventPublisher);
    }

    @Test
    @DisplayName("approve() publishes ClaimExpenseApprovedEvent with derived expenseReference and parent-claim currency")
    void approve_publishesEventWithCompletePayload() {
        UUID expenseId = UUID.fromString("a0b1c2d3-e4f5-6789-abcd-ef0123456789");
        UUID claimId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("45000.00");

        Claim parentClaim = Claim.builder()
                .claimNumber("CLM-2026-00041")
                .currencyCode("NGN")
                .build();
        parentClaim.setId(claimId);

        ClaimExpense pending = ClaimExpense.builder()
                .claim(parentClaim)
                .status(ClaimExpenseStatus.PENDING)
                .expenseType(ClaimExpenseType.SURVEYOR_FEE)
                .vendorId(vendorId)
                .vendorName("Lagos Loss Surveyors Ltd")
                .amount(amount)
                .description("Site survey + assessment")
                .build();
        pending.setId(expenseId);

        when(expenseRepository.findByIdAndDeletedAtIsNull(expenseId)).thenReturn(Optional.of(pending));
        when(expenseRepository.save(any(ClaimExpense.class))).thenAnswer(inv -> inv.getArgument(0));

        service.approve(expenseId);

        ArgumentCaptor<ClaimExpenseApprovedEvent> captor = ArgumentCaptor.forClass(ClaimExpenseApprovedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ClaimExpenseApprovedEvent event = captor.getValue();

        assertThat(event.expenseId()).as("expenseId").isEqualTo(expenseId);
        assertThat(event.expenseReference())
                .as("expenseReference is the producer-assembled string used for the JE narrative + idempotency triple")
                .isEqualTo("CLM-2026-00041-EXP-a0b1c2d3");
        assertThat(event.claimId()).as("claimId — from parent Claim").isEqualTo(claimId);
        assertThat(event.claimNumber()).as("claimNumber — from parent Claim").isEqualTo("CLM-2026-00041");
        assertThat(event.vendorId()).as("vendorId").isEqualTo(vendorId);
        assertThat(event.vendorName()).as("vendorName").isEqualTo("Lagos Loss Surveyors Ltd");
        assertThat(event.expenseType())
                .as("expenseType is the enum name() string, not the enum itself — fixed contract")
                .isEqualTo("SURVEYOR_FEE");
        assertThat(event.amount()).as("amount").isEqualByComparingTo(amount);
        assertThat(event.currencyCode())
                .as("currencyCode is sourced from the parent Claim, not ClaimExpense — load-bearing")
                .isEqualTo("NGN");
    }

    @Test
    @DisplayName("approve() does NOT publish when expense is not PENDING — status guard regression")
    void approve_doesNotPublishWhenStatusIsWrong() {
        UUID expenseId = UUID.randomUUID();
        ClaimExpense alreadyApproved = ClaimExpense.builder()
                .status(ClaimExpenseStatus.APPROVED)
                .build();
        alreadyApproved.setId(expenseId);

        when(expenseRepository.findByIdAndDeletedAtIsNull(expenseId)).thenReturn(Optional.of(alreadyApproved));

        assertThatThrownBy(() -> service.approve(expenseId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PENDING");

        verify(eventPublisher, never()).publishEvent(Mockito.<ClaimExpenseApprovedEvent>any());
    }
}
