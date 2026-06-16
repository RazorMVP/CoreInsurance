package com.nubeero.cia.endorsement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nubeero.cia.documents.DocumentGenerationService;
import com.nubeero.cia.policy.PolicyRepository;
import io.temporal.client.WorkflowClient;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link EndorsementService#calculatePremiumAdjustment} — the pro-rata endorsement
 * premium {@code (newAnnual − oldAnnual) × remainingDays / 365}, HALF_UP to 2dp. Pure arithmetic;
 * the service's collaborators are never exercised on this path (constructed with Mockito mocks).
 *
 * <p>Expected values are hand-computed from the rule and assert the correct outcome.
 */
class EndorsementProRataTest {

    // calculatePremiumAdjustment is pure — these mocks are never invoked. Constructor-arg order
    // mirrors EndorsementService's @RequiredArgsConstructor field order.
    private final EndorsementService service = new EndorsementService(
            mock(EndorsementRepository.class),
            mock(EndorsementNumberService.class),
            mock(PolicyRepository.class),
            mock(WorkflowClient.class),
            mock(ApplicationEventPublisher.class),
            mock(DocumentGenerationService.class));

    @Test
    void increase_isPositiveAdditionalPremium() {
        // (730000 − 365000) × 100 / 365 = 365000 × 100 / 365 = 100000.00
        assertThat(service.calculatePremiumAdjustment(
                new BigDecimal("365000"), new BigDecimal("730000"), 100))
                .isEqualByComparingTo("100000.00");
    }

    @Test
    void decrease_isNegativeReturnPremium() {
        // (365000 − 730000) × 100 / 365 = −100000.00
        assertThat(service.calculatePremiumAdjustment(
                new BigDecimal("730000"), new BigDecimal("365000"), 100))
                .isEqualByComparingTo("-100000.00");
    }

    @Test
    void equalPremiums_returnZero() {
        assertThat(service.calculatePremiumAdjustment(
                new BigDecimal("500000"), new BigDecimal("500000"), 100))
                .isEqualByComparingTo("0");
    }

    @Test
    void nullNewPremium_returnsZero() {
        assertThat(service.calculatePremiumAdjustment(new BigDecimal("500000"), null, 100))
                .isEqualByComparingTo("0");
    }

    @Test
    void fullRemainingYear_isFullDifference() {
        // (200000 − 100000) × 365 / 365 = 100000.00
        assertThat(service.calculatePremiumAdjustment(
                new BigDecimal("100000"), new BigDecimal("200000"), 365))
                .isEqualByComparingTo("100000.00");
    }

    @Test
    void roundsHalfUpToTwoDecimals() {
        // (200000 − 100000) × 30 / 365 = 3000000 / 365 = 8219.1780... ⇒ 8219.18
        assertThat(service.calculatePremiumAdjustment(
                new BigDecimal("100000"), new BigDecimal("200000"), 30))
                .isEqualByComparingTo("8219.18");
    }

    @Test
    void zeroRemainingDays_isZeroViaArithmetic() {
        // Distinct from the two short-circuit-to-zero cases: premiums differ (no short-circuit), so
        // this exercises the multiply-divide branch — (200000 − 100000) × 0 / 365 = 0. Business
        // boundary: an endorsement effective on the last day of cover earns no pro-rata adjustment.
        assertThat(service.calculatePremiumAdjustment(
                new BigDecimal("100000"), new BigDecimal("200000"), 0))
                .isEqualByComparingTo("0");
    }
}
