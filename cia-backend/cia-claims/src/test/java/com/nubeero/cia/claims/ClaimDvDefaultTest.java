package com.nubeero.cia.claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nubeero.cia.claims.dto.GenerateDvRequest;
import com.nubeero.cia.common.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Focused unit test for the DV-amount default in {@link ClaimService#generateDv}
 * — the only money rule in the claims DV path: when the request omits an amount
 * and the claim has no DV amount yet, the DV amount falls back to the claim's
 * {@code approvedAmount}; an explicit request amount overrides; an already-set
 * DV amount is preserved. Also pins the APPROVED/SETTLED status guard.
 *
 * <p>Mockito rather than a Testcontainers IT: the rule is branchy state logic,
 * not a formula, so only {@code claimRepository} (the one collaborator
 * {@code generateDv}/{@code findOrThrow} touch) needs to be mocked. Part of the
 * {@code money-math-test-coverage} backlog (Slice 2).
 */
@ExtendWith(MockitoExtension.class)
class ClaimDvDefaultTest {

    @Mock ClaimRepository claimRepository;
    @InjectMocks ClaimService service;

    private static Claim approvedClaim(String approvedAmount, String dvAmount) {
        return Claim.builder()
                .status(ClaimStatus.APPROVED)
                .approvedAmount(new BigDecimal(approvedAmount))
                .dvAmount(dvAmount == null ? null : new BigDecimal(dvAmount))
                .build();
    }

    private void stubFindAndSave(Claim claim) {
        when(claimRepository.findByIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(Claim.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void generateDv_amountOmitted_andNoExistingDvAmount_defaultsToApprovedAmount() {
        Claim claim = approvedClaim("500000", null);
        stubFindAndSave(claim);

        Claim result = service.generateDv(UUID.randomUUID(),
                new GenerateDvRequest(DvType.OWN_DAMAGE, null));

        assertThat(result.getDvAmount()).isEqualByComparingTo("500000");
        assertThat(result.getDvType()).isEqualTo(DvType.OWN_DAMAGE);
    }

    @Test
    void generateDv_explicitAmount_overridesApprovedAmount() {
        Claim claim = approvedClaim("500000", null);
        stubFindAndSave(claim);

        Claim result = service.generateDv(UUID.randomUUID(),
                new GenerateDvRequest(DvType.THIRD_PARTY, new BigDecimal("300000")));

        assertThat(result.getDvAmount()).isEqualByComparingTo("300000");
    }

    @Test
    void generateDv_amountOmitted_butDvAmountAlreadySet_isPreserved() {
        // dvAmount already 250,000 — must NOT be overwritten with approvedAmount 500,000
        Claim claim = approvedClaim("500000", "250000");
        stubFindAndSave(claim);

        Claim result = service.generateDv(UUID.randomUUID(),
                new GenerateDvRequest(DvType.EX_GRATIA, null));

        assertThat(result.getDvAmount()).isEqualByComparingTo("250000");
    }

    @Test
    void generateDv_nonApprovedOrSettledStatus_throwsInvalidStatus_andDoesNotSave() {
        Claim registered = Claim.builder()
                .status(ClaimStatus.REGISTERED).approvedAmount(new BigDecimal("500000")).build();
        when(claimRepository.findByIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(registered));

        assertThatThrownBy(() -> service.generateDv(UUID.randomUUID(),
                new GenerateDvRequest(DvType.OWN_DAMAGE, null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrorCode()).isEqualTo("INVALID_STATUS"));

        verify(claimRepository, never()).save(any());
    }
}
