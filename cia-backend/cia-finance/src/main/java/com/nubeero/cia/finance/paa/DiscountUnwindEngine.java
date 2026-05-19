package com.nubeero.cia.finance.paa;

import com.nubeero.cia.finance.dto.JournalEntryLineRequest;
import com.nubeero.cia.finance.dto.JournalEntryResponse;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
import com.nubeero.cia.finance.gl.FiscalPeriod;
import com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException;
import com.nubeero.cia.finance.gl.FiscalPeriodRepository;
import com.nubeero.cia.finance.gl.JournalEntryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * IFRS 17 §87-92 Insurance Finance Income/Expense engine — recognises the
 * discount unwind on the Liability for Incurred Claims for one fiscal
 * period. Module 12 Phase 2 Slice 2.6.
 *
 * <p>Runs AFTER {@link LicEngine}: the LIC engine has written paa_lic
 * rows with {@code discount_unwind = 0} and {@code closing_balance = opening
 * + incurred − paid}. This engine, for tenants that elect LIC discounting
 * (paa_config.discount_lic = TRUE), computes the unwind on each row's
 * opening balance, posts the corresponding JE, and updates the paa_lic row
 * so the roll-forward identity closes:
 *
 * <pre>
 *   closing = opening + incurred − paid + case_reserve_change + ibnr_change
 *           + risk_adjustment_change + discount_unwind
 * </pre>
 *
 * <p>Unwind formula (v1, simple flat-rate model):
 *
 * <pre>
 *   unwind = opening_balance × discount_rate × (period_days / 365)
 * </pre>
 *
 * <p>v2 will adopt the term-structure approach in §B72-B85 (yield-curve
 * lookup keyed off claim-payment expected timing), at which point this
 * engine's signature stays the same and only {@link #computeUnwind} grows
 * the curve-aware logic.
 *
 * <h2>Routing — §88(b) OCI election</h2>
 * <p>Per the tenant's {@link PaaConfig#isOciElection()}:
 * <ul>
 *   <li>FALSE → P&amp;L: {@code Dr 5520 (Insurance finance expense) / Cr 2140 (LIC)}</li>
 *   <li>TRUE → OCI: {@code Dr 3430 (Insurance finance OCI) / Cr 2140 (LIC)}</li>
 * </ul>
 * Routing is per-tenant, not per-group, in v1 (the IFRS 17 election can be
 * portfolio-scoped but PaaConfig is a singleton today).
 *
 * <h2>Idempotency</h2>
 * <p>Three layers:
 * <ul>
 *   <li>Per-row: {@code paa_lic.discount_unwind = 0} means "not yet
 *       unwound" — re-running skips rows that already carry a non-zero
 *       unwind. Edge case: a row with zero opening balance produces zero
 *       unwind regardless, so re-running it is a no-op (correct).</li>
 *   <li>JE gateway: {@code uq_journal_entry_idempotency} on
 *       (source_module, source_event_type, source_reference). Reference is
 *       {@code period_id + ":" + group_id}.</li>
 *   <li>Service: the per-row check happens before any DB write so a re-run
 *       of an already-unwound period is a clean no-op.</li>
 * </ul>
 *
 * <h2>v1 simplifications</h2>
 * <ul>
 *   <li>Flat rate. v2: yield-curve lookup per claim-payment timing.</li>
 *   <li>No change-in-rate effect (§87(b)). v2 will recognise rate-curve
 *       movement between periods as a separate component.</li>
 *   <li>OCI election scoped to tenant. v2 may scope it to portfolio.</li>
 *   <li>Cross-currency unwind not supported (single rate from PaaConfig).</li>
 * </ul>
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class DiscountUnwindEngine {

    // ── Hardcoded COA codes ──────────────────────────────────────────────────
    /** Insurance finance expense (P&L). V32 seed, ifrs17_role=INSURANCE_FINANCE_EXPENSE. */
    static final String COA_INSURANCE_FINANCE_EXPENSE = "5520";

    /** Insurance finance OCI (§88(b) election). V32 seed, ifrs17_role=INSURANCE_FINANCE_OCI. */
    static final String COA_INSURANCE_FINANCE_OCI = "3430";

    /** LIC — Outstanding claims reserve. V32 seed, ifrs17_role=LIC_OCR. */
    static final String COA_LIC_OCR = "2140";

    // ── Idempotency triple slot values ───────────────────────────────────────
    static final String MODULE_PAA = "paa";
    static final String EVENT_PAA_DISCOUNT_UNWIND = "PAA_DISCOUNT_UNWIND";

    private static final int MONEY_SCALE = 2;
    private static final int FRACTION_SCALE = 12;
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365L);

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final PaaConfigRepository paaConfigRepository;
    private final PaaLicRepository licRepository;
    private final JournalEntryService journalEntryService;

    /**
     * Recognise discount unwind for {@code periodId} across every paa_lic
     * row in the period. Idempotent at the row grain — already-unwound rows
     * are skipped.
     */
    public DiscountUnwindResult recognise(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        PaaConfig config = paaConfigRepository.findActive().orElseGet(this::createDefaultConfig);

        if (!config.isDiscountLic()) {
            log.info("Discount unwind disabled by paa_config.discount_lic = FALSE for period {} — no-op", periodId);
            return new DiscountUnwindResult(period.getId(), true, null, 0, 0, BigDecimal.ZERO, List.of());
        }

        BigDecimal rate = config.getDiscountRate();
        boolean ociElection = config.isOciElection();
        String routing = ociElection ? "OCI" : "P&L";

        log.info("Discount unwind starting for period {} ({} → {}); rate {}; routing {}",
            periodId, period.getStartDate(), period.getEndDate(), rate, routing);

        List<PaaLic> licRows = licRepository.findByPeriodIdAndDeletedAtIsNullOrderByGroupIdAsc(periodId);

        BigDecimal totalUnwind = BigDecimal.ZERO;
        int groupsWithJe = 0;
        List<DiscountUnwindResult.GroupUnwindEntry> entries = new ArrayList<>();
        long periodDays = ChronoUnit.DAYS.between(period.getStartDate(), period.getEndDate()) + 1L;

        for (PaaLic lic : licRows) {
            // Idempotent skip: row already carries a non-zero unwind from a prior run.
            if (lic.getDiscountUnwind().signum() != 0) {
                log.debug("Skipping paa_lic {} (group {}) — discount_unwind already set", lic.getId(), lic.getGroup().getId());
                continue;
            }

            BigDecimal unwind = computeUnwind(lic.getOpeningBalance(), rate, periodDays);

            UUID jeId = null;
            if (unwind.signum() != 0) {
                jeId = postJe(lic, period, unwind, ociElection);
                lic.setDiscountUnwind(unwind);
                lic.setClosingBalance(lic.getClosingBalance().add(unwind));
                licRepository.save(lic);
                groupsWithJe++;
                totalUnwind = totalUnwind.add(unwind);
            } else {
                log.debug("Skipping paa_lic {} (group {}) — zero opening balance produces zero unwind",
                    lic.getId(), lic.getGroup().getId());
            }

            entries.add(new DiscountUnwindResult.GroupUnwindEntry(
                lic.getGroup().getId(),
                lic.getOpeningBalance(),
                unwind,
                lic.getClosingBalance(),
                jeId));
        }

        log.info("Discount unwind complete for period {} — {} groups processed, {} JEs posted, total unwind {}",
            periodId, entries.size(), groupsWithJe, totalUnwind);

        return new DiscountUnwindResult(period.getId(), false, routing,
            entries.size(), groupsWithJe, totalUnwind, entries);
    }

    /**
     * {@code unwind = opening × rate × (periodDays / 365)} rounded HALF_UP
     * at {@code MONEY_SCALE}.
     */
    static BigDecimal computeUnwind(BigDecimal opening, BigDecimal rate, long periodDays) {
        if (opening.signum() == 0 || rate.signum() == 0 || periodDays == 0) return BigDecimal.ZERO;
        return opening
            .multiply(rate)
            .multiply(BigDecimal.valueOf(periodDays))
            .divide(DAYS_PER_YEAR, FRACTION_SCALE, RoundingMode.HALF_UP)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private UUID postJe(PaaLic lic, FiscalPeriod period, BigDecimal unwind, boolean ociElection) {
        String idempotencyRef = period.getId() + ":" + lic.getGroup().getId();
        String debitAccount = ociElection ? COA_INSURANCE_FINANCE_OCI : COA_INSURANCE_FINANCE_EXPENSE;
        String routing = ociElection ? "OCI" : "P&L";

        JournalEntryLineRequest debit = new JournalEntryLineRequest(
            debitAccount, unwind, BigDecimal.ZERO, lic.getCurrencyCode(),
            lic.getGroup().getCohortYear(),
            lic.getGroup().getPortfolio().getId(),
            lic.getGroup().getId(),
            null, null);

        JournalEntryLineRequest credit = new JournalEntryLineRequest(
            COA_LIC_OCR, BigDecimal.ZERO, unwind, lic.getCurrencyCode(),
            lic.getGroup().getCohortYear(),
            lic.getGroup().getPortfolio().getId(),
            lic.getGroup().getId(),
            null, null);

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            period.getEndDate(),
            MODULE_PAA,
            EVENT_PAA_DISCOUNT_UNWIND,
            idempotencyRef,
            "Discount unwind (" + routing + ") for group " + lic.getGroup().getPortfolio().getCode() + "/"
                + lic.getGroup().getCohortYear() + "/" + lic.getGroup().getOnerousness()
                + " for period " + period.getStartDate() + " to " + period.getEndDate(),
            List.of(debit, credit));

        JournalEntryResponse je = journalEntryService.post(request);
        return je.id();
    }

    /**
     * Lazy-create the default {@link PaaConfig} singleton on first need. The
     * default row preserves v1's "no discounting / no OCI" semantics —
     * subsequent finance team configuration will toggle these knobs.
     */
    private PaaConfig createDefaultConfig() {
        PaaConfig cfg = new PaaConfig();
        // Hibernate-managed defaults already set discountLic = false, ociElection = false,
        // raMethod = CONFIDENCE_LEVEL, acquisitionCashflowMethod = EXPENSE_AS_INCURRED.
        return paaConfigRepository.save(cfg);
    }
}
