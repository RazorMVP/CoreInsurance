package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TreatyService#activate}'s quota-share participant-share
 * validation (backlog {@code quota-share-sum-100-validation}).
 *
 * <p>Quota-share participant shares are the <em>ceded</em> percentage; the
 * insurer's retention is the remainder ({@link AllocationService#applyQuotaShare}
 * computes {@code retained = sumInsured − ceded}). So the reinsurer shares must
 * not <em>exceed</em> 100% (over-cession) — but they need not equal 100% (that
 * would force zero retention). These tests pin exactly that boundary.
 */
@ExtendWith(MockitoExtension.class)
class TreatyServiceTest {

    @Mock private RiTreatyRepository treatyRepository;
    @Mock private RiTreatyParticipantRepository participantRepository;
    @Mock private com.nubeero.cia.setup.org.ReinsuranceCompanyRepository reinsuranceCompanyRepository;

    private TreatyService service() {
        return new TreatyService(treatyRepository, participantRepository, reinsuranceCompanyRepository);
    }

    private RiTreatyParticipant participant(String pct) {
        return RiTreatyParticipant.builder()
                .reinsuranceCompanyId(UUID.randomUUID())
                .reinsuranceCompanyName("Re Co")
                .sharePercentage(new BigDecimal(pct))
                .build();
    }

    private RiTreaty draft(TreatyType type, RiTreatyParticipant... participants) {
        return RiTreaty.builder()
                .treatyType(type)
                .status(TreatyStatus.DRAFT)
                .participants(List.of(participants))
                .build();
    }

    @Test
    void activate_quotaShare_rejectsWhenSharesExceed100_noSave() {
        UUID id = UUID.randomUUID();
        RiTreaty treaty = draft(TreatyType.QUOTA_SHARE, participant("70"), participant("50")); // 120%
        when(treatyRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(treaty));

        assertThatThrownBy(() -> service().activate(id))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot exceed 100%");

        // The guard runs before the status flip + persist — nothing is saved, status stays DRAFT.
        verify(treatyRepository, never()).save(any());
        assertThat(treaty.getStatus()).isEqualTo(TreatyStatus.DRAFT);
    }

    @Test
    void activate_quotaShare_allowsUnder100_retentionIsRemainder() {
        UUID id = UUID.randomUUID();
        RiTreaty treaty = draft(TreatyType.QUOTA_SHARE, participant("40"), participant("20")); // 60% ceded, 40% retained
        when(treatyRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(treaty));
        when(treatyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RiTreaty activated = service().activate(id);

        assertThat(activated.getStatus()).isEqualTo(TreatyStatus.ACTIVE);
    }

    @Test
    void activate_quotaShare_allowsExactly100() {
        UUID id = UUID.randomUUID();
        RiTreaty treaty = draft(TreatyType.QUOTA_SHARE, participant("60"), participant("40")); // exactly 100%
        when(treatyRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(treaty));
        when(treatyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service().activate(id).getStatus()).isEqualTo(TreatyStatus.ACTIVE);
    }

    @Test
    void activate_surplus_doesNotEnforceQuotaShareSum() {
        UUID id = UUID.randomUUID();
        // 140% across participants would be invalid for QS but SURPLUS uses retention/surplus-line logic.
        RiTreaty treaty = draft(TreatyType.SURPLUS, participant("70"), participant("70"));
        when(treatyRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(treaty));
        when(treatyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service().activate(id).getStatus()).isEqualTo(TreatyStatus.ACTIVE);
    }
}
