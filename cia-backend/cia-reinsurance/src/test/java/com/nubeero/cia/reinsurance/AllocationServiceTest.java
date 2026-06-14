package com.nubeero.cia.reinsurance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nubeero.cia.common.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the reinsurance cession math in {@link AllocationService}. Pure Mockito — the
 * public {@code allocate(...)} returns the fully-computed {@link RiAllocation} (amounts + lines),
 * so mocking the three collaborators exercises surplus / quota-share / XOL / line-distribution
 * arithmetic with no Spring context or database.
 *
 * <p>Expected values are hand-computed from the documented cession rules (see each test). They
 * assert the CORRECT business outcome; a failure signals a production bug to surface, not a test
 * to relax.
 */
class AllocationServiceTest {

    private RiAllocationRepository allocationRepository;
    private RiTreatyRepository treatyRepository;
    private RiNumberService numberService;
    private AllocationService service;

    private final UUID treatyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        allocationRepository = mock(RiAllocationRepository.class);
        treatyRepository = mock(RiTreatyRepository.class);
        numberService = mock(RiNumberService.class);
        when(numberService.nextAllocationNumber()).thenReturn("RI-0001");
        when(allocationRepository.save(any(RiAllocation.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AllocationService(allocationRepository, treatyRepository, numberService);
    }

    private RiTreatyParticipant participant(String sharePct, String commissionPct, String name) {
        return RiTreatyParticipant.builder()
                .reinsuranceCompanyId(UUID.randomUUID())
                .reinsuranceCompanyName(name)
                .sharePercentage(new BigDecimal(sharePct))
                .commissionRate(new BigDecimal(commissionPct))
                .build();
    }

    private RiAllocation allocateWith(RiTreaty treaty, String si, String premium) {
        when(treatyRepository.findByIdAndDeletedAtIsNull(treatyId)).thenReturn(Optional.of(treaty));
        return service.allocate(UUID.randomUUID(), "POL-1", treatyId,
                new BigDecimal(si), new BigDecimal(premium), "NGN", null);
    }

    // ─── SURPLUS ──────────────────────────────────────────────────────────

    @Test
    void surplus_retainsUpToLimit_cedesRemainderWithinCapacity_premiumProRata() {
        // retention 10M, capacity 40M, SI 30M, premium 300k.
        // retained=10M; toAllocate=20M; ceded=min(20M,40M)=20M; excess=0.
        // retainedPrem = 300000 × 10M/30M = 100000.00; cededPrem = 200000.00.
        RiTreaty treaty = RiTreaty.builder()
                .treatyType(TreatyType.SURPLUS).status(TreatyStatus.ACTIVE)
                .retentionLimit(new BigDecimal("10000000"))
                .surplusCapacity(new BigDecimal("40000000"))
                .participants(List.of(participant("60", "10", "Re A"),
                                      participant("40", "5", "Re B")))
                .build();

        RiAllocation a = allocateWith(treaty, "30000000", "300000");

        assertThat(a.getRetainedAmount()).isEqualByComparingTo("10000000");
        assertThat(a.getCededAmount()).isEqualByComparingTo("20000000");
        assertThat(a.getExcessAmount()).isEqualByComparingTo("0");
        assertThat(a.getRetainedPremium()).isEqualByComparingTo("100000.00");
        assertThat(a.getCededPremium()).isEqualByComparingTo("200000.00");

        assertThat(a.getLines()).hasSize(2);
        RiAllocationLine reA = a.getLines().stream()
                .filter(l -> l.getReinsuranceCompanyName().equals("Re A")).findFirst().orElseThrow();
        RiAllocationLine reB = a.getLines().stream()
                .filter(l -> l.getReinsuranceCompanyName().equals("Re B")).findFirst().orElseThrow();
        assertThat(reA.getCededAmount()).isEqualByComparingTo("12000000.00");   // 20M × 0.6
        assertThat(reA.getCededPremium()).isEqualByComparingTo("120000.00");    // 200k × 0.6
        assertThat(reA.getCommissionAmount()).isEqualByComparingTo("12000.00"); // 120k × 10%
        assertThat(reB.getCededAmount()).isEqualByComparingTo("8000000.00");    // 20M × 0.4
        assertThat(reB.getCededPremium()).isEqualByComparingTo("80000.00");     // 200k × 0.4
        assertThat(reB.getCommissionAmount()).isEqualByComparingTo("4000.00");  // 80k × 5%

        BigDecimal lineSiSum = a.getLines().stream().map(RiAllocationLine::getCededAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal linePremSum = a.getLines().stream().map(RiAllocationLine::getCededPremium)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(lineSiSum).isEqualByComparingTo(a.getCededAmount());
        assertThat(linePremSum).isEqualByComparingTo(a.getCededPremium());
    }

    @Test
    void surplus_tagsExcessBeyondRetentionPlusCapacity() {
        // retention 10M, capacity 15M, SI 40M, premium 400k.
        // retained=10M; toAllocate=30M; ceded=min(30M,15M)=15M; excess=15M.
        // retainedPrem = 400000 × 10M/40M = 100000.00; cededPrem = 300000.00.
        RiTreaty treaty = RiTreaty.builder()
                .treatyType(TreatyType.SURPLUS).status(TreatyStatus.ACTIVE)
                .retentionLimit(new BigDecimal("10000000"))
                .surplusCapacity(new BigDecimal("15000000"))
                .participants(List.of(participant("100", "0", "Re A")))
                .build();

        RiAllocation a = allocateWith(treaty, "40000000", "400000");

        assertThat(a.getRetainedAmount()).isEqualByComparingTo("10000000");
        assertThat(a.getCededAmount()).isEqualByComparingTo("15000000");
        assertThat(a.getExcessAmount()).isEqualByComparingTo("15000000");
        assertThat(a.getRetainedPremium()).isEqualByComparingTo("100000.00");
        assertThat(a.getCededPremium()).isEqualByComparingTo("300000.00");
    }

    @Test
    void surplus_siWithinRetention_cedesNothing_noLines() {
        // retention 10M, SI 8M, premium 80k. retained=8M; ceded=0; excess=0; retainedPrem=80000.00.
        RiTreaty treaty = RiTreaty.builder()
                .treatyType(TreatyType.SURPLUS).status(TreatyStatus.ACTIVE)
                .retentionLimit(new BigDecimal("10000000"))
                .surplusCapacity(new BigDecimal("40000000"))
                .participants(List.of(participant("100", "10", "Re A")))
                .build();

        RiAllocation a = allocateWith(treaty, "8000000", "80000");

        assertThat(a.getRetainedAmount()).isEqualByComparingTo("8000000");
        assertThat(a.getCededAmount()).isEqualByComparingTo("0");
        assertThat(a.getExcessAmount()).isEqualByComparingTo("0");
        assertThat(a.getRetainedPremium()).isEqualByComparingTo("80000.00");
        assertThat(a.getCededPremium()).isEqualByComparingTo("0");
        assertThat(a.getLines()).isEmpty();
    }

    // ─── QUOTA SHARE ──────────────────────────────────────────────────────

    @Test
    void quotaShare_cedesByTotalParticipantPercentage_andSplitsLines() {
        // participants 40% (comm 10%) + 20% (comm 5%) ⇒ totalCededPct 60.
        // SI 10M, premium 100k. cededSI=6M; retainedSI=4M; cededPrem=60k; retainedPrem=40k; excess=0.
        RiTreaty treaty = RiTreaty.builder()
                .treatyType(TreatyType.QUOTA_SHARE).status(TreatyStatus.ACTIVE)
                .participants(List.of(participant("40", "10", "Re A"),
                                      participant("20", "5", "Re B")))
                .build();

        RiAllocation a = allocateWith(treaty, "10000000", "100000");

        assertThat(a.getRetainedAmount()).isEqualByComparingTo("4000000.00");
        assertThat(a.getCededAmount()).isEqualByComparingTo("6000000.00");
        assertThat(a.getExcessAmount()).isEqualByComparingTo("0");
        assertThat(a.getRetainedPremium()).isEqualByComparingTo("40000.00");
        assertThat(a.getCededPremium()).isEqualByComparingTo("60000.00");

        assertThat(a.getLines()).hasSize(2);
        RiAllocationLine reA = a.getLines().stream()
                .filter(l -> l.getReinsuranceCompanyName().equals("Re A")).findFirst().orElseThrow();
        RiAllocationLine reB = a.getLines().stream()
                .filter(l -> l.getReinsuranceCompanyName().equals("Re B")).findFirst().orElseThrow();
        // Re A: 40/60 ⇒ 6M×=4,000,000.00 ; 60k×=40,000.00 ; comm 10% = 4,000.00
        assertThat(reA.getCededAmount()).isEqualByComparingTo("4000000.00");
        assertThat(reA.getCededPremium()).isEqualByComparingTo("40000.00");
        assertThat(reA.getCommissionAmount()).isEqualByComparingTo("4000.00");
        // Re B: 20/60 ⇒ 6M×=2,000,000.00 ; 60k×=20,000.00 ; comm 5% = 1,000.00
        assertThat(reB.getCededAmount()).isEqualByComparingTo("2000000.00");
        assertThat(reB.getCededPremium()).isEqualByComparingTo("20000.00");
        assertThat(reB.getCommissionAmount()).isEqualByComparingTo("1000.00");

        // Line cessions reconcile to the allocation's ceded totals (catches per-line rounding leak).
        BigDecimal qsLineSiSum = a.getLines().stream().map(RiAllocationLine::getCededAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal qsLinePremSum = a.getLines().stream().map(RiAllocationLine::getCededPremium)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(qsLineSiSum).isEqualByComparingTo(a.getCededAmount());
        assertThat(qsLinePremSum).isEqualByComparingTo(a.getCededPremium());
    }

    // ─── XOL ──────────────────────────────────────────────────────────────

    @Test
    void xol_retainsEverything_cedesNothing_noLines() {
        RiTreaty treaty = RiTreaty.builder()
                .treatyType(TreatyType.XOL).status(TreatyStatus.ACTIVE)
                .participants(List.of(participant("100", "10", "Re A")))
                .build();

        RiAllocation a = allocateWith(treaty, "10000000", "100000");

        assertThat(a.getRetainedAmount()).isEqualByComparingTo("10000000");
        assertThat(a.getCededAmount()).isEqualByComparingTo("0");
        assertThat(a.getExcessAmount()).isEqualByComparingTo("0");
        assertThat(a.getRetainedPremium()).isEqualByComparingTo("100000");
        assertThat(a.getCededPremium()).isEqualByComparingTo("0");
        assertThat(a.getLines()).isEmpty();
    }

    // ─── GUARD ────────────────────────────────────────────────────────────

    @Test
    void allocate_rejectsNonActiveTreaty() {
        RiTreaty draft = RiTreaty.builder()
                .treatyType(TreatyType.SURPLUS).status(TreatyStatus.DRAFT)
                .retentionLimit(new BigDecimal("10000000"))
                .surplusCapacity(new BigDecimal("40000000"))
                .build();
        when(treatyRepository.findByIdAndDeletedAtIsNull(treatyId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.allocate(UUID.randomUUID(), "POL-1", treatyId,
                new BigDecimal("30000000"), new BigDecimal("300000"), "NGN", null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ACTIVE");
    }
}
