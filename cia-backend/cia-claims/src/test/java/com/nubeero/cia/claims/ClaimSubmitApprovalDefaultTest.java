package com.nubeero.cia.claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nubeero.cia.claims.dto.SubmitClaimRequest;
import com.nubeero.cia.common.exception.BusinessRuleException;
import io.temporal.client.WorkflowClient;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Focused unit test for the second claims money default — the
 * approved-amount fallback in {@link ClaimService#submitForApproval}: when the
 * submit request omits an amount, the approved amount falls back to the claim's
 * {@code reserveAmount}; an explicit request amount overrides; submitting a
 * non-RESERVED claim is rejected. This is the sibling of the DV-amount default
 * covered by {@link ClaimDvDefaultTest} — together they cover the "claims money
 * defaults" surface (added after the Slice 2 review noted only the DV default
 * was tested).
 *
 * <p>Mockito rather than a Testcontainers IT: the rule is a default-assignment
 * branch, not a formula. {@code workflowClient} is mocked so the Temporal
 * dispatch inside {@code submitForApproval} short-circuits (the service wraps it
 * in a try/catch and logs on failure), leaving the amount logic + persistence
 * observable.
 */
@ExtendWith(MockitoExtension.class)
class ClaimSubmitApprovalDefaultTest {

    @Mock ClaimRepository claimRepository;
    @Mock WorkflowClient workflowClient;
    @InjectMocks ClaimService service;

    private static Claim reservedClaim(String reserveAmount) {
        return Claim.builder()
                .status(ClaimStatus.RESERVED)
                .reserveAmount(new BigDecimal(reserveAmount))
                .build();
    }

    private void stubFindAndSave(Claim claim) {
        when(claimRepository.findByIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void submitForApproval_amountOmitted_defaultsToReserveAmount() {
        // reserve 400,000, request approvedAmount null → approved defaults to 400,000
        Claim claim = reservedClaim("400000");
        stubFindAndSave(claim);

        Claim result = service.submitForApproval(UUID.randomUUID(), new SubmitClaimRequest(null));

        assertThat(result.getApprovedAmount()).isEqualByComparingTo("400000");
        assertThat(result.getStatus()).isEqualTo(ClaimStatus.PENDING_APPROVAL);
    }

    @Test
    void submitForApproval_explicitAmount_overridesReserveAmount() {
        // reserve 400,000 but request approvedAmount 350,000 → approved = 350,000
        Claim claim = reservedClaim("400000");
        stubFindAndSave(claim);

        Claim result = service.submitForApproval(UUID.randomUUID(),
                new SubmitClaimRequest(new BigDecimal("350000")));

        assertThat(result.getApprovedAmount()).isEqualByComparingTo("350000");
        assertThat(result.getStatus()).isEqualTo(ClaimStatus.PENDING_APPROVAL);
    }

    @Test
    void submitForApproval_nonReservedStatus_throwsInvalidStatus_andDoesNotSave() {
        Claim registered = Claim.builder()
                .status(ClaimStatus.REGISTERED).reserveAmount(new BigDecimal("400000")).build();
        when(claimRepository.findByIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(registered));

        assertThatThrownBy(() -> service.submitForApproval(UUID.randomUUID(), new SubmitClaimRequest(null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrorCode()).isEqualTo("INVALID_STATUS"));

        verify(claimRepository, never()).save(any());
    }
}
