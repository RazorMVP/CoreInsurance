package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.finance.dto.JournalEntryLineRequest;
import com.nubeero.cia.finance.dto.JournalEntryResponse;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito tests for {@link JournalEntryService}. Validates the contract
 * every downstream closure slice binds to:
 * <ul>
 *   <li>Balance enforcement (D6) before any persist.</li>
 *   <li>Inactive account rejection (D7) on the post path; reversal path
 *       skips the active check.</li>
 *   <li>Idempotency lookup with clean 409 mapping (D8).</li>
 *   <li>Single-reversal rule (D11) — original goes REVERSED, mirror is
 *       POSTED with {@code reversal_of} FK.</li>
 * </ul>
 *
 * <p>Test design note: Mockito's inline mock-maker can't redefine concrete
 * Spring services under Java 25's tightened agent rules. We instead inject
 * real {@link ChartOfAccountService} and {@link FiscalPeriodResolver}
 * instances backed by mocked repositories — same depth of isolation, but
 * routed through interfaces (which mock cleanly via dynamic proxies).
 */
@ExtendWith(MockitoExtension.class)
class JournalEntryServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 14);
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-05-14T09:00:00Z"), ZoneOffset.UTC);

    @Mock
    private JournalEntryRepository repository;

    @Mock
    private ChartOfAccountRepository coaRepository;

    @Mock
    private FiscalPeriodRepository fiscalPeriodRepository;

    private JournalEntryService service;
    private ChartOfAccountService chartOfAccountService;
    private FiscalPeriodResolver fiscalPeriodResolver;

    private ChartOfAccount cash;
    private ChartOfAccount revenue;
    private ChartOfAccount inactive;
    private UUID periodId;
    private FiscalPeriod period;

    @BeforeEach
    void seed() {
        chartOfAccountService = new ChartOfAccountService(coaRepository);
        fiscalPeriodResolver = new FiscalPeriodResolver(fiscalPeriodRepository);
        service = new JournalEntryService(repository, chartOfAccountService, fiscalPeriodResolver, FIXED_CLOCK);

        cash = newAccount("1110", "Cash on hand", AccountType.ASSET, true);
        revenue = newAccount("4110", "Premium revenue", AccountType.INCOME, true);
        inactive = newAccount("9999", "Retired account", AccountType.ASSET, false);

        period = new FiscalPeriod();
        periodId = UUID.randomUUID();
        period.setId(periodId);
        period.setPeriodType(FiscalPeriodType.MONTH);
        period.setStartDate(LocalDate.of(2026, 5, 1));
        period.setEndDate(LocalDate.of(2026, 5, 31));
    }

    @Test
    @DisplayName("post happy path persists header + mirror lines and stamps audit fields")
    void postHappyPath() {
        when(coaRepository.findByCodeAndDeletedAtIsNull("1110")).thenReturn(Optional.of(cash));
        when(coaRepository.findByCodeAndDeletedAtIsNull("4110")).thenReturn(Optional.of(revenue));
        when(repository.findBySourceModuleAndSourceEventTypeAndSourceReference("finance", "MANUAL", "ref-1"))
            .thenReturn(Optional.empty());
        stubMonthPeriod(TODAY);
        when(repository.save(any(JournalEntry.class))).thenAnswer(invocation -> {
            JournalEntry je = invocation.getArgument(0);
            je.setId(UUID.randomUUID());
            return je;
        });

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            TODAY, "finance", "MANUAL", "ref-1", "Test posting",
            List.of(
                line("1110", "100.00", "0.00"),
                line("4110", "0.00",   "100.00")));

        JournalEntryResponse response = service.post(request);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(repository, times(1)).save(captor.capture());
        JournalEntry saved = captor.getValue();
        assertThat(saved.getPostingDate()).isEqualTo(TODAY);
        assertThat(saved.getBusinessDate()).isEqualTo(TODAY);
        assertThat(saved.getPeriodId()).isEqualTo(periodId);
        assertThat(saved.getSourceModule()).isEqualTo("finance");
        assertThat(saved.getSourceEventType()).isEqualTo("MANUAL");
        assertThat(saved.getSourceReference()).isEqualTo("ref-1");
        assertThat(saved.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(saved.getLines()).hasSize(2);
        assertThat(saved.getLines().get(0).getDebitAmount()).isEqualByComparingTo("100.00");
        assertThat(saved.getLines().get(1).getCreditAmount()).isEqualByComparingTo("100.00");

        assertThat(response.lines()).hasSize(2);
        assertThat(response.status()).isEqualTo(JournalEntryStatus.POSTED);
    }

    @Test
    @DisplayName("post throws UnbalancedJournalEntryException when debits != credits")
    void postUnbalanced() {
        PostJournalEntryRequest request = new PostJournalEntryRequest(
            TODAY, "finance", "MANUAL", "ref-2", "Bad posting",
            List.of(
                line("1110", "100.00", "0.00"),
                line("4110", "0.00",   "99.00")));

        assertThatThrownBy(() -> service.post(request))
            .isInstanceOf(UnbalancedJournalEntryException.class)
            .hasMessageContaining("debits=100.00")
            .hasMessageContaining("credits=99.00")
            .hasMessageContaining("delta=1.00");

        verify(repository, never()).save(any(JournalEntry.class));
    }

    @Test
    @DisplayName("post throws JournalEntryDuplicateException when idempotency triple is already in use")
    void postDuplicate() {
        JournalEntry existing = new JournalEntry();
        existing.setId(UUID.randomUUID());
        when(repository.findBySourceModuleAndSourceEventTypeAndSourceReference("finance", "POLICY_APPROVED", "POL-1"))
            .thenReturn(Optional.of(existing));

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            TODAY, "finance", "POLICY_APPROVED", "POL-1", "Dup",
            List.of(
                line("1110", "100.00", "0.00"),
                line("4110", "0.00",   "100.00")));

        assertThatThrownBy(() -> service.post(request))
            .isInstanceOf(JournalEntryDuplicateException.class)
            .hasMessageContaining("POLICY_APPROVED")
            .hasMessageContaining("POL-1");

        verify(repository, never()).save(any(JournalEntry.class));
    }

    @Test
    @DisplayName("post throws InactiveAccountException when a line targets an inactive account")
    void postInactiveAccount() {
        when(coaRepository.findByCodeAndDeletedAtIsNull("9999")).thenReturn(Optional.of(inactive));
        when(repository.findBySourceModuleAndSourceEventTypeAndSourceReference("finance", "MANUAL", "ref-3"))
            .thenReturn(Optional.empty());
        stubMonthPeriod(TODAY);

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            TODAY, "finance", "MANUAL", "ref-3", "Inactive target",
            List.of(
                line("9999", "100.00", "0.00"),
                line("4110", "0.00",   "100.00")));

        assertThatThrownBy(() -> service.post(request))
            .isInstanceOf(InactiveAccountException.class)
            .hasMessageContaining("9999");

        verify(repository, never()).save(any(JournalEntry.class));
    }

    @Test
    @DisplayName("post rejects a line with both debit and credit zero")
    void postBothZero() {
        PostJournalEntryRequest request = new PostJournalEntryRequest(
            TODAY, "finance", "MANUAL", "ref-4", "Empty line",
            List.of(
                line("1110", "0.00", "0.00"),
                line("4110", "0.00", "0.00")));

        assertThatThrownBy(() -> service.post(request))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("exactly one of debit/credit");
    }

    @Test
    @DisplayName("post rejects a line with both debit and credit > 0")
    void postBothNonZero() {
        PostJournalEntryRequest request = new PostJournalEntryRequest(
            TODAY, "finance", "MANUAL", "ref-5", "Mixed line",
            List.of(
                line("1110", "100.00", "50.00"),
                line("4110", "0.00",   "50.00")));

        assertThatThrownBy(() -> service.post(request))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("exactly one of debit/credit");
    }

    @Test
    @DisplayName("post rejects amounts with scale > 2")
    void postScaleTooLarge() {
        PostJournalEntryRequest request = new PostJournalEntryRequest(
            TODAY, "finance", "MANUAL", "ref-6", "Scale 3",
            List.of(
                line("1110", "100.123", "0"),
                line("4110", "0",       "100.123")));

        assertThatThrownBy(() -> service.post(request))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("2 decimal places");
    }

    @Test
    @DisplayName("reverse mirrors the original, flips original to REVERSED, sets reversal_of FK")
    void reverseHappyPath() {
        JournalEntry original = newOriginalEntry();
        when(repository.findByIdAndDeletedAtIsNull(original.getId())).thenReturn(Optional.of(original));
        stubMonthPeriod(TODAY);
        when(repository.save(any(JournalEntry.class))).thenAnswer(invocation -> {
            JournalEntry je = invocation.getArgument(0);
            if (je.getId() == null) {
                je.setId(UUID.randomUUID());
            }
            return je;
        });

        JournalEntryResponse response = service.reverse(original.getId(), "Booking error");

        assertThat(original.getStatus()).isEqualTo(JournalEntryStatus.REVERSED);
        assertThat(response.status()).isEqualTo(JournalEntryStatus.POSTED);
        assertThat(response.reversalOf()).isEqualTo(original.getId());
        assertThat(response.sourceEventType()).isEqualTo(JournalEntryService.REVERSAL_EVENT_TYPE);
        assertThat(response.sourceReference()).isEqualTo(original.getId().toString());
        assertThat(response.narrative())
            .startsWith("REVERSAL of JE " + original.getId() + ": ")
            .endsWith("Booking error");
        // Mirror: original debit on cash → credit on cash; original credit on revenue → debit on revenue.
        assertThat(response.lines()).hasSize(2);
        assertThat(response.lines().get(0).accountCode()).isEqualTo("1110");
        assertThat(response.lines().get(0).debitAmount()).isEqualByComparingTo("0.00");
        assertThat(response.lines().get(0).creditAmount()).isEqualByComparingTo("100.00");
        assertThat(response.lines().get(1).accountCode()).isEqualTo("4110");
        assertThat(response.lines().get(1).debitAmount()).isEqualByComparingTo("100.00");
        assertThat(response.lines().get(1).creditAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("reverse throws JournalEntryAlreadyReversedException when original is REVERSED")
    void reverseAlreadyReversed() {
        JournalEntry original = newOriginalEntry();
        original.setStatus(JournalEntryStatus.REVERSED);
        when(repository.findByIdAndDeletedAtIsNull(original.getId())).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.reverse(original.getId(), "Try again"))
            .isInstanceOf(JournalEntryAlreadyReversedException.class);
    }

    @Test
    @DisplayName("reverse throws JournalEntryAlreadyReversedException for an entry that is itself a reversal")
    void reverseOfReversalRejected() {
        JournalEntry reversalEntry = newOriginalEntry();
        reversalEntry.setReversalOf(UUID.randomUUID());
        when(repository.findByIdAndDeletedAtIsNull(reversalEntry.getId())).thenReturn(Optional.of(reversalEntry));

        assertThatThrownBy(() -> service.reverse(reversalEntry.getId(), "Chain"))
            .isInstanceOf(JournalEntryAlreadyReversedException.class);
    }

    @Test
    @DisplayName("reverse throws BusinessRuleException for a DRAFT entry")
    void reverseDraftRejected() {
        JournalEntry draft = newOriginalEntry();
        draft.setStatus(JournalEntryStatus.DRAFT);
        when(repository.findByIdAndDeletedAtIsNull(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.reverse(draft.getId(), "Reason"))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("Only POSTED");
    }

    @Test
    @DisplayName("reverse skips the active-account check (d7) — works even when accounts have been retired")
    void reverseAgainstInactiveAccount() {
        JournalEntry original = newOriginalEntry();
        // Flip both line accounts to inactive.
        for (JournalEntryLine line : original.getLines()) {
            line.getAccount().setActive(false);
        }
        when(repository.findByIdAndDeletedAtIsNull(original.getId())).thenReturn(Optional.of(original));
        stubMonthPeriod(TODAY);
        when(repository.save(any(JournalEntry.class))).thenAnswer(invocation -> {
            JournalEntry je = invocation.getArgument(0);
            if (je.getId() == null) {
                je.setId(UUID.randomUUID());
            }
            return je;
        });

        JournalEntryResponse response = service.reverse(original.getId(), "Year-end cleanup");
        assertThat(response.status()).isEqualTo(JournalEntryStatus.POSTED);
    }

    @Test
    @DisplayName("findById hit returns full response with lines")
    void findByIdHit() {
        JournalEntry original = newOriginalEntry();
        when(repository.findByIdAndDeletedAtIsNull(original.getId())).thenReturn(Optional.of(original));

        JournalEntryResponse response = service.findById(original.getId());
        assertThat(response.id()).isEqualTo(original.getId());
        assertThat(response.lines()).hasSize(2);
    }

    @Test
    @DisplayName("findById miss throws JournalEntryNotFoundException")
    void findByIdMiss() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id))
            .isInstanceOf(JournalEntryNotFoundException.class)
            .hasMessageContaining(id.toString());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubMonthPeriod(LocalDate businessDate) {
        when(fiscalPeriodRepository
            .findFirstByPeriodTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
                FiscalPeriodType.MONTH, businessDate, businessDate))
            .thenReturn(Optional.of(period));
    }

    private static ChartOfAccount newAccount(String code, String name, AccountType type, boolean active) {
        ChartOfAccount a = new ChartOfAccount();
        a.setId(UUID.randomUUID());
        a.setCode(code);
        a.setName(name);
        a.setAccountType(type);
        a.setActive(active);
        return a;
    }

    private static JournalEntryLineRequest line(String accountCode, String debit, String credit) {
        return new JournalEntryLineRequest(
            accountCode, new BigDecimal(debit), new BigDecimal(credit), null, null, null, null, null, null);
    }

    private JournalEntry newOriginalEntry() {
        JournalEntry je = new JournalEntry();
        je.setId(UUID.randomUUID());
        je.setPostingDate(TODAY);
        je.setBusinessDate(TODAY);
        je.setPeriodId(periodId);
        je.setSourceModule("finance");
        je.setSourceEventType("MANUAL");
        je.setSourceReference("ref-original");
        je.setNarrative("Original posting");
        je.setPostedBy("system");
        je.setStatus(JournalEntryStatus.POSTED);

        JournalEntryLine debitLine = new JournalEntryLine();
        debitLine.setLineNo(1);
        debitLine.setAccount(cash);
        debitLine.setDebitAmount(new BigDecimal("100.00"));
        debitLine.setCreditAmount(new BigDecimal("0.00"));
        debitLine.setCurrencyCode("NGN");
        je.addLine(debitLine);

        JournalEntryLine creditLine = new JournalEntryLine();
        creditLine.setLineNo(2);
        creditLine.setAccount(revenue);
        creditLine.setDebitAmount(new BigDecimal("0.00"));
        creditLine.setCreditAmount(new BigDecimal("100.00"));
        creditLine.setCurrencyCode("NGN");
        je.addLine(creditLine);

        return je;
    }
}
