package com.nubeero.cia.finance.gl;

import com.nubeero.cia.finance.dto.TrialBalanceResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito tests for {@link TrialBalanceService}. Verifies the
 * presentation invariant that each account row carries a balance on exactly
 * one side (the netted side) and that the footer summary reflects the raw
 * totals — not the per-account netting.
 */
@ExtendWith(MockitoExtension.class)
class TrialBalanceServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 5, 14);
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-05-14T18:00:00Z"), ZoneOffset.UTC);

    @Mock
    private JournalEntryLineRepository lineRepository;

    private TrialBalanceService service;

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        service = new TrialBalanceService(lineRepository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("trialBalanceAsOf renders debit-side accounts (assets/expenses) with a positive debit balance")
    void debitSideAccount() {
        UUID cashId = UUID.randomUUID();
        when(lineRepository.aggregateByAccountAsOf(eq(AS_OF))).thenReturn(List.<Object[]>of(
            new Object[] { cashId, "1110", "Cash on hand", AccountType.ASSET, new BigDecimal("500.00"), new BigDecimal("100.00") }));
        when(lineRepository.totalsAsOf(eq(AS_OF))).thenReturn(
            List.<Object[]>of(new Object[] { new BigDecimal("500.00"), new BigDecimal("100.00"), 2L }));

        TrialBalanceResponse response = service.trialBalanceAsOf(AS_OF);

        assertThat(response.asOf()).isEqualTo(AS_OF);
        assertThat(response.generatedAt()).isEqualTo(Instant.parse("2026-05-14T18:00:00Z"));
        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().get(0).accountCode()).isEqualTo("1110");
        assertThat(response.lines().get(0).debitBalance()).isEqualByComparingTo("400.00");
        assertThat(response.lines().get(0).creditBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("trialBalanceAsOf renders credit-side accounts (liability/income) with a positive credit balance")
    void creditSideAccount() {
        UUID revenueId = UUID.randomUUID();
        when(lineRepository.aggregateByAccountAsOf(eq(AS_OF))).thenReturn(List.<Object[]>of(
            new Object[] { revenueId, "4110", "Premium revenue", AccountType.INCOME, new BigDecimal("0.00"), new BigDecimal("500.00") }));
        when(lineRepository.totalsAsOf(eq(AS_OF))).thenReturn(
            List.<Object[]>of(new Object[] { new BigDecimal("0.00"), new BigDecimal("500.00"), 1L }));

        TrialBalanceResponse response = service.trialBalanceAsOf(AS_OF);

        assertThat(response.lines().get(0).debitBalance()).isEqualByComparingTo("0.00");
        assertThat(response.lines().get(0).creditBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("footer.balanced is true when total debits equal total credits")
    void footerBalanced() {
        when(lineRepository.aggregateByAccountAsOf(eq(AS_OF))).thenReturn(List.of(
            new Object[] { UUID.randomUUID(), "1110", "Cash", AccountType.ASSET, new BigDecimal("500.00"), new BigDecimal("0.00") },
            new Object[] { UUID.randomUUID(), "4110", "Revenue", AccountType.INCOME, new BigDecimal("0.00"), new BigDecimal("500.00") }));
        when(lineRepository.totalsAsOf(eq(AS_OF))).thenReturn(
            List.<Object[]>of(new Object[] { new BigDecimal("500.00"), new BigDecimal("500.00"), 2L }));

        TrialBalanceResponse response = service.trialBalanceAsOf(AS_OF);

        assertThat(response.footer().balanced()).isTrue();
        assertThat(response.footer().totalDebits()).isEqualByComparingTo("500.00");
        assertThat(response.footer().totalCredits()).isEqualByComparingTo("500.00");
        assertThat(response.footer().lineCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("footer.balanced is false when the GL is out of balance")
    void footerUnbalanced() {
        when(lineRepository.aggregateByAccountAsOf(eq(AS_OF))).thenReturn(List.of(
            new Object[] { UUID.randomUUID(), "1110", "Cash", AccountType.ASSET, new BigDecimal("500.00"), new BigDecimal("0.00") },
            new Object[] { UUID.randomUUID(), "4110", "Revenue", AccountType.INCOME, new BigDecimal("0.00"), new BigDecimal("450.00") }));
        when(lineRepository.totalsAsOf(eq(AS_OF))).thenReturn(
            List.<Object[]>of(new Object[] { new BigDecimal("500.00"), new BigDecimal("450.00"), 2L }));

        TrialBalanceResponse response = service.trialBalanceAsOf(AS_OF);
        assertThat(response.footer().balanced()).isFalse();
    }

    @Test
    @DisplayName("empty general ledger yields empty lines and a zero balanced footer")
    void empty() {
        when(lineRepository.aggregateByAccountAsOf(eq(AS_OF))).thenReturn(List.of());
        when(lineRepository.totalsAsOf(eq(AS_OF))).thenReturn(
            List.<Object[]>of(new Object[] { BigDecimal.ZERO, BigDecimal.ZERO, 0L }));

        TrialBalanceResponse response = service.trialBalanceAsOf(AS_OF);
        assertThat(response.lines()).isEmpty();
        assertThat(response.footer().balanced()).isTrue();
        assertThat(response.footer().totalDebits()).isEqualByComparingTo("0");
        assertThat(response.footer().totalCredits()).isEqualByComparingTo("0");
        assertThat(response.footer().lineCount()).isZero();
    }

    @Test
    @DisplayName("null asOf throws IllegalArgumentException — the date is required for filtering")
    void asOfRequired() {
        assertThatThrownBy(() -> service.trialBalanceAsOf(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("asOf");
    }
}
