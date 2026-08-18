package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.event.ClaimApprovedEvent;
import com.nubeero.cia.common.event.ClaimExpenseApprovedEvent;
import com.nubeero.cia.common.event.ClaimSettledEvent;
import com.nubeero.cia.common.event.EndorsementApprovedEvent;
import com.nubeero.cia.common.event.FacPremiumCededEvent;
import com.nubeero.cia.common.event.PolicyApprovedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito tests for {@link SubledgerPostingService}. Verifies that each
 * @EventListener method translates its event into a balanced JE with the
 * correct shape (Dr/Cr codes, amount, business date, idempotency triple,
 * narrative).
 *
 * <p>Wiring pattern follows the same Java 25-Mockito workaround as
 * {@code JournalEntryServiceTest}: concrete Spring services can't be inline-
 * mocked under Java 25, so we construct real services backed by mocked
 * Spring Data repository interfaces (which mock cleanly via dynamic proxies).
 * Captures land at the {@code JournalEntryRepository.save} call, the
 * narrowest point at which the JE shape is observable.
 */
@ExtendWith(MockitoExtension.class)
class SubledgerPostingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 15);
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);

    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private ChartOfAccountRepository coaRepository;
    @Mock private FiscalPeriodRepository fiscalPeriodRepository;
    @Mock private FiscalYearRepository fiscalYearRepository;
    @Mock private PostingRuleRepository postingRuleRepository;

    private SubledgerPostingService service;
    private UUID periodId;
    private FiscalPeriod monthPeriod;

    @BeforeEach
    void wire() {
        ChartOfAccountService coaService = new ChartOfAccountService(coaRepository);
        FiscalPeriodResolver resolver = new FiscalPeriodResolver(fiscalPeriodRepository);
        JournalEntryService journalEntryService = new JournalEntryService(
            journalEntryRepository, coaService, resolver, FIXED_CLOCK);
        PostingRuleService postingRuleService = new PostingRuleService(postingRuleRepository);
        // Slice 1.10a — PolicyClassResolver dispatch is exercised by
        // SubledgerPostingCoverageContractTest under cia-api, not this
        // unit test. Passing a Mockito-backed resolver lets the existing
        // assertions stay focused on the rule-dispatch contract.
        PolicyClassResolver policyClassResolver = org.mockito.Mockito.mock(PolicyClassResolver.class);
        service = new SubledgerPostingService(journalEntryService, postingRuleService,
            policyClassResolver, FIXED_CLOCK);

        periodId = UUID.randomUUID();
        monthPeriod = new FiscalPeriod();
        monthPeriod.setId(periodId);
        monthPeriod.setPeriodType(FiscalPeriodType.MONTH);
        monthPeriod.setStartDate(LocalDate.of(2026, 5, 1));
        monthPeriod.setEndDate(LocalDate.of(2026, 5, 31));
    }

    // ── PolicyApproved ───────────────────────────────────────────────────────

    @Test
    @DisplayName("onPolicyApproved posts Dr 1310 / Cr 2110 for net premium at policy start date")
    void onPolicyApproved_HappyPath() {
        stubRule(SubledgerPostingService.EVENT_POLICY_APPROVED, "1310", "2110",
            "Premium booking for policy %s");
        stubAccount("1310", "Premium receivable - Direct", AccountType.ASSET);
        stubAccount("2110", "LRC BEL", AccountType.LIABILITY);
        LocalDate policyStart = LocalDate.of(2026, 5, 10);
        stubMonthPeriodCovering(policyStart);
        stubIdempotencyClear();
        stubSaveEchoesId();

        UUID policyId = UUID.randomUUID();
        service.onPolicyApproved(new PolicyApprovedEvent(
            policyId, "POL-001", UUID.randomUUID(), "Acme Corp", null, null,
            "Motor Comprehensive", new BigDecimal("500000.00"), "NGN",
            LocalDate.of(2027, 5, 9), UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("10000000.00"), policyStart,
            null, null, null, null,
            policyStart)); // approvalDate = start (test keeps business_date unchanged)

        JournalEntry saved = captureSavedJe();
        assertThat(saved.getBusinessDate()).isEqualTo(policyStart);
        assertThat(saved.getSourceModule()).isEqualTo(SubledgerPostingService.MODULE_POLICY);
        assertThat(saved.getSourceEventType()).isEqualTo(SubledgerPostingService.EVENT_POLICY_APPROVED);
        assertThat(saved.getSourceReference()).isEqualTo(policyId.toString());
        assertThat(saved.getNarrative()).isEqualTo("Premium booking for policy POL-001");
        assertThat(saved.getLines()).hasSize(2);
        assertThat(saved.getLines().get(0).getAccount().getCode()).isEqualTo("1310");
        assertThat(saved.getLines().get(0).getDebitAmount()).isEqualByComparingTo("500000.00");
        assertThat(saved.getLines().get(0).getCreditAmount()).isEqualByComparingTo("0");
        assertThat(saved.getLines().get(1).getAccount().getCode()).isEqualTo("2110");
        assertThat(saved.getLines().get(1).getCreditAmount()).isEqualByComparingTo("500000.00");
    }

    @Test
    @DisplayName("onPolicyApproved skips JE when net premium is zero")
    void onPolicyApproved_ZeroPremiumSkips() {
        service.onPolicyApproved(new PolicyApprovedEvent(
            UUID.randomUUID(), "POL-FREE", UUID.randomUUID(), "x", null, null,
            "x", BigDecimal.ZERO, "NGN",
            LocalDate.of(2027, 1, 1), UUID.randomUUID(), UUID.randomUUID(),
            BigDecimal.ZERO, LocalDate.of(2026, 1, 1),
            null, null, null, null,
            LocalDate.of(2026, 1, 1))); // approvalDate = start (test keeps business_date unchanged)

        verify(journalEntryRepository, never()).save(any(JournalEntry.class));
    }

    @Test
    @DisplayName("onPolicyApproved propagates PostingRuleNotFoundException when rule is missing")
    void onPolicyApproved_MissingRule() {
        when(postingRuleRepository.findBySourceEventTypeAndActiveTrueAndDeletedAtIsNull(
            SubledgerPostingService.EVENT_POLICY_APPROVED)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.onPolicyApproved(new PolicyApprovedEvent(
            UUID.randomUUID(), "POL-X", UUID.randomUUID(), "x", null, null,
            "x", new BigDecimal("100.00"), "NGN",
            LocalDate.of(2027, 1, 1), UUID.randomUUID(), UUID.randomUUID(),
            BigDecimal.ZERO, LocalDate.of(2026, 5, 10),
            null, null, null, null,
            LocalDate.of(2026, 5, 10)))) // approvalDate = start (test keeps business_date unchanged)
            .isInstanceOf(PostingRuleNotFoundException.class)
            .hasMessageContaining(SubledgerPostingService.EVENT_POLICY_APPROVED);

        verify(journalEntryRepository, never()).save(any(JournalEntry.class));
    }

    // ── ClaimApproved ────────────────────────────────────────────────────────

    @Test
    @DisplayName("onClaimApproved posts Dr 5110 / Cr 2140 for approved amount at today")
    void onClaimApproved_HappyPath() {
        stubRule(SubledgerPostingService.EVENT_CLAIM_APPROVED, "5110", "2140",
            "Claim approval for %s on policy %s");
        stubAccount("5110", "Incurred claims", AccountType.EXPENSE);
        stubAccount("2140", "LIC OCR", AccountType.LIABILITY);
        stubMonthPeriodCovering(TODAY);
        stubIdempotencyClear();
        stubSaveEchoesId();

        UUID claimId = UUID.randomUUID();
        service.onClaimApproved(new ClaimApprovedEvent(
            claimId, "CLM-007", UUID.randomUUID(), "POL-007",
            UUID.randomUUID(), "Insured", null, null, "Motor",
            new BigDecimal("120000.00"), "NGN"));

        JournalEntry saved = captureSavedJe();
        assertThat(saved.getBusinessDate()).isEqualTo(TODAY);
        assertThat(saved.getSourceReference()).isEqualTo(claimId.toString());
        assertThat(saved.getNarrative()).isEqualTo("Claim approval for CLM-007 on policy POL-007");
        assertThat(saved.getLines().get(0).getAccount().getCode()).isEqualTo("5110");
        assertThat(saved.getLines().get(0).getDebitAmount()).isEqualByComparingTo("120000.00");
        assertThat(saved.getLines().get(1).getAccount().getCode()).isEqualTo("2140");
    }

    // ── ClaimSettled ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("onClaimSettled uses settledAt as business date (UTC LocalDate) + dvAmount")
    void onClaimSettled_HappyPath() {
        stubRule(SubledgerPostingService.EVENT_CLAIM_SETTLED, "2140", "1120",
            "Settlement of claim %s");
        stubAccount("2140", "LIC OCR", AccountType.LIABILITY);
        stubAccount("1120", "Bank current accounts", AccountType.ASSET);
        LocalDate settledDate = LocalDate.of(2026, 5, 15);
        stubMonthPeriodCovering(settledDate);
        stubIdempotencyClear();
        stubSaveEchoesId();

        UUID claimId = UUID.randomUUID();
        service.onClaimSettled(new ClaimSettledEvent(
            claimId, "CLM-099", UUID.randomUUID(), "POL-099",
            UUID.randomUUID(), "Insured", new BigDecimal("85000.00"), "NGN",
            Instant.parse("2026-05-15T11:30:00Z")));

        JournalEntry saved = captureSavedJe();
        assertThat(saved.getBusinessDate()).isEqualTo(settledDate);
        assertThat(saved.getSourceReference()).isEqualTo(claimId.toString());
        assertThat(saved.getLines().get(0).getDebitAmount()).isEqualByComparingTo("85000.00");
    }

    // ── ClaimExpenseApproved ─────────────────────────────────────────────────

    @Test
    @DisplayName("onClaimExpenseApproved posts Dr 5140 / Cr 2350 at today")
    void onClaimExpenseApproved_HappyPath() {
        stubRule(SubledgerPostingService.EVENT_CLAIM_EXPENSE_APPROVED, "5140", "2350",
            "Claim expense %s on claim %s");
        stubAccount("5140", "Other direct expenses", AccountType.EXPENSE);
        stubAccount("2350", "Claims payable", AccountType.LIABILITY);
        stubMonthPeriodCovering(TODAY);
        stubIdempotencyClear();
        stubSaveEchoesId();

        UUID expenseId = UUID.randomUUID();
        service.onClaimExpenseApproved(new ClaimExpenseApprovedEvent(
            expenseId, "EXP-1", UUID.randomUUID(), "CLM-1",
            UUID.randomUUID(), "Vendor Co", "SURVEY", new BigDecimal("15000.00"), "NGN"));

        JournalEntry saved = captureSavedJe();
        assertThat(saved.getNarrative()).isEqualTo("Claim expense EXP-1 on claim CLM-1");
        assertThat(saved.getLines().get(0).getAccount().getCode()).isEqualTo("5140");
        assertThat(saved.getLines().get(1).getAccount().getCode()).isEqualTo("2350");
    }

    // ── EndorsementApproved — sign dispatch ──────────────────────────────────

    @Test
    @DisplayName("EndorsementApproved with positive adjustment uses ADDITIONAL rule (Dr 1310 / Cr 2110)")
    void onEndorsementApproved_Additional() {
        stubRule(SubledgerPostingService.EVENT_ENDORSEMENT_PREMIUM_ADDITIONAL, "1310", "2110",
            "Endorsement %s additional premium for policy %s");
        stubAccount("1310", "Premium receivable - Direct", AccountType.ASSET);
        stubAccount("2110", "LRC BEL", AccountType.LIABILITY);
        stubMonthPeriodCovering(TODAY);
        stubIdempotencyClear();
        stubSaveEchoesId();

        service.onEndorsementApproved(new EndorsementApprovedEvent(
            UUID.randomUUID(), "ENDO-1", UUID.randomUUID(), "POL-1",
            UUID.randomUUID(), "Cust", null, null, "Motor",
            new BigDecimal("25000.00"), "NGN"));

        JournalEntry saved = captureSavedJe();
        assertThat(saved.getSourceEventType()).isEqualTo(SubledgerPostingService.EVENT_ENDORSEMENT_PREMIUM_ADDITIONAL);
        assertThat(saved.getLines().get(0).getAccount().getCode()).isEqualTo("1310");
        assertThat(saved.getLines().get(0).getDebitAmount()).isEqualByComparingTo("25000.00");
    }

    @Test
    @DisplayName("EndorsementApproved with negative adjustment uses REFUND rule (Dr 2110 / Cr 1310) with absolute amount")
    void onEndorsementApproved_Refund() {
        stubRule(SubledgerPostingService.EVENT_ENDORSEMENT_PREMIUM_REFUND, "2110", "1310",
            "Endorsement %s premium refund for policy %s");
        stubAccount("2110", "LRC BEL", AccountType.LIABILITY);
        stubAccount("1310", "Premium receivable - Direct", AccountType.ASSET);
        stubMonthPeriodCovering(TODAY);
        stubIdempotencyClear();
        stubSaveEchoesId();

        service.onEndorsementApproved(new EndorsementApprovedEvent(
            UUID.randomUUID(), "ENDO-CXL", UUID.randomUUID(), "POL-CXL",
            UUID.randomUUID(), "Cust", null, null, "Motor",
            new BigDecimal("-7500.00"), "NGN"));

        JournalEntry saved = captureSavedJe();
        assertThat(saved.getSourceEventType()).isEqualTo(SubledgerPostingService.EVENT_ENDORSEMENT_PREMIUM_REFUND);
        assertThat(saved.getLines().get(0).getAccount().getCode()).isEqualTo("2110");
        assertThat(saved.getLines().get(0).getDebitAmount()).isEqualByComparingTo("7500.00"); // absolute
    }

    @Test
    @DisplayName("EndorsementApproved with zero adjustment skips JE entirely")
    void onEndorsementApproved_Zero() {
        service.onEndorsementApproved(new EndorsementApprovedEvent(
            UUID.randomUUID(), "ENDO-ADDR", UUID.randomUUID(), "POL-1",
            UUID.randomUUID(), "Cust", null, null, "Motor",
            BigDecimal.ZERO, "NGN"));

        verify(journalEntryRepository, never()).save(any(JournalEntry.class));
    }

    // ── FacPremiumCeded — §65-netted 2-line ──────────────────────────────────

    @Test
    @DisplayName("onFacPremiumCeded posts 2 lines: Dr 1410 (net), Cr 2310 (net) — §65 commission-netting, no posting_rule lookup")
    void onFacPremiumCeded_HappyPath() {
        stubAccount("1410", "Reinsurance - LRC asset", AccountType.ASSET);
        stubAccount("2310", "RI premium payable", AccountType.LIABILITY);
        stubMonthPeriodCovering(TODAY);
        stubIdempotencyClear();
        stubSaveEchoesId();

        UUID facCoverId = UUID.randomUUID();
        service.onFacPremiumCeded(new FacPremiumCededEvent(
            facCoverId, "FAC-001", UUID.randomUUID(), "POL-FAC",
            UUID.randomUUID(), "Munich Re",
            new BigDecimal("100000.00"), new BigDecimal("20000.00"),
            new BigDecimal("80000.00"), "NGN"));

        JournalEntry saved = captureSavedJe();
        assertThat(saved.getSourceModule()).isEqualTo(SubledgerPostingService.MODULE_REINSURANCE);
        assertThat(saved.getSourceEventType()).isEqualTo(SubledgerPostingService.EVENT_FAC_PREMIUM_CEDED);
        assertThat(saved.getSourceReference()).isEqualTo(facCoverId.toString());
        assertThat(saved.getNarrative()).isEqualTo("Outward FAC FAC-001 ceded to Munich Re");
        assertThat(saved.getLines()).hasSize(2);
        assertThat(saved.getLines().get(0).getAccount().getCode()).isEqualTo("1410");
        assertThat(saved.getLines().get(0).getDebitAmount()).isEqualByComparingTo("80000.00");
        assertThat(saved.getLines().get(1).getAccount().getCode()).isEqualTo("2310");
        assertThat(saved.getLines().get(1).getCreditAmount()).isEqualByComparingTo("80000.00");
        // Invariant: Σ debits == Σ credits (JournalEntryService.post enforces this)
        // Dr 80000 (net) == Cr 80000 (net) ✓ — commission (20000) is netted into the
        // asset, never posted to a standalone commission-income account.
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubRule(String eventType, String dr, String cr, String narrativeTemplate) {
        PostingRule rule = new PostingRule();
        rule.setSourceEventType(eventType);
        rule.setDebitAccountCode(dr);
        rule.setCreditAccountCode(cr);
        rule.setNarrativeTemplate(narrativeTemplate);
        rule.setActive(true);
        when(postingRuleRepository.findBySourceEventTypeAndActiveTrueAndDeletedAtIsNull(eventType))
            .thenReturn(Optional.of(rule));
    }

    private void stubAccount(String code, String name, AccountType type) {
        ChartOfAccount account = new ChartOfAccount();
        account.setId(UUID.randomUUID());
        account.setCode(code);
        account.setName(name);
        account.setAccountType(type);
        account.setActive(true);
        when(coaRepository.findByCodeAndDeletedAtIsNull(code)).thenReturn(Optional.of(account));
    }

    private void stubMonthPeriodCovering(LocalDate businessDate) {
        when(fiscalPeriodRepository
            .findFirstByPeriodTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
                FiscalPeriodType.MONTH, businessDate, businessDate))
            .thenReturn(Optional.of(monthPeriod));
    }

    private void stubIdempotencyClear() {
        when(journalEntryRepository.findBySourceModuleAndSourceEventTypeAndSourceReference(
            any(), any(), any())).thenReturn(Optional.empty());
    }

    private void stubSaveEchoesId() {
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> {
            JournalEntry je = invocation.getArgument(0);
            if (je.getId() == null) {
                je.setId(UUID.randomUUID());
            }
            return je;
        });
    }

    private JournalEntry captureSavedJe() {
        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        return captor.getValue();
    }
}
