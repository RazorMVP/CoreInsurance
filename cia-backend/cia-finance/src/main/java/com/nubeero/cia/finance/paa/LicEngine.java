package com.nubeero.cia.finance.paa;

import com.nubeero.cia.finance.gl.FiscalPeriod;
import com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException;
import com.nubeero.cia.finance.gl.FiscalPeriodRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IFRS 17 PAA Liability for Incurred Claims measurement engine.
 * Module 12 Phase 2 Slice 2.4.
 *
 * <p>The LIC mirror of {@link LrcEngine}: for a target fiscal period, walks
 * every IFRS 17 group of contracts and computes the LIC roll-forward by
 * aggregating claim activity (approvals + settlements) from the claims
 * table — joined to the group via {@code contract_group_assignment}
 * filtered to {@code contract_type = 'POLICY'} (direct claims only — FAC
 * LIC is a later slice of the FAC / IFRS-17 PAA workstream).
 *
 * <h2>v1 does NOT post a JE</h2>
 * <p>Unlike {@link LrcEngine} which posts insurance-revenue JEs, the LIC
 * engine writes only the {@link PaaLic} disclosure roll-forward row. The
 * underlying GL state is already correct: {@code SubledgerPostingService}
 * (Slice 1.5) posts
 * <ul>
 *   <li>{@code Dr 5110 (Incurred claims) / Cr 2140 (Claim payable)} on every
 *       {@code CLAIM_APPROVED} event,</li>
 *   <li>{@code Dr 2140 / Cr 1120 (Bank)} on every {@code CLAIM_SETTLED} event,</li>
 * </ul>
 * so the {@code 2140} balance already represents the simplified PAA LIC under
 * v1's no-IBNR / no-RA / no-discounting assumptions. The {@link PaaLic} table
 * is the §103 disclosure record on top of that GL state.
 *
 * <p>Future slices that <em>do</em> need to post JEs:
 * <ul>
 *   <li>Slice 2.7 — IBNR + Risk Adjustment recognition (new JE per group:
 *       Dr 5120/5130 / Cr 2120-IBNR + 2120-RA).</li>
 *   <li>Slice 2.x — discount unwind (interest expense per period for
 *       discounted LIC).</li>
 *   <li>Slice 2.5 — reclassification from {@code 2140} (sub-ledger payable)
 *       into {@code 2120} (IFRS-17 LIC) for balance-sheet presentation.</li>
 * </ul>
 *
 * <h2>Roll-forward identity</h2>
 * <pre>
 *   opening_balance + claims_incurred − claims_paid = closing_balance
 * </pre>
 * Where:
 * <ul>
 *   <li>{@code opening_balance} = approved-not-yet-settled liability at
 *       period.start, summed across all policies in the group;</li>
 *   <li>{@code claims_incurred} = sum of {@code approved_amount} for claims
 *       whose {@code approved_at} falls in the period;</li>
 *   <li>{@code claims_paid} = sum of {@code dv_amount} (fall-back to
 *       {@code approved_amount}) for claims whose {@code settled_at} falls
 *       in the period;</li>
 *   <li>{@code closing_balance} = approved-not-yet-settled liability at
 *       period.end.</li>
 * </ul>
 *
 * <p>The identity holds by construction when {@code dv_amount = approved_amount}.
 * Real-world claims may settle for less or more — the resulting residual sits
 * in {@code 2140} as a reserve true-up and surfaces as the discrepancy between
 * incurred − paid and (closing − opening). Slice 2.7 will recognise this gap
 * as a case-reserve movement.
 *
 * <h2>v1 simplifications</h2>
 * <ul>
 *   <li>{@code case_reserve_change}, {@code ibnr_estimate}, {@code ibnr_change},
 *       {@code risk_adjustment}, {@code risk_adjustment_change},
 *       {@code discount_unwind} all written as zero. The DB columns exist for
 *       Slice 2.7's actuarial extensions.</li>
 *   <li>Cross-currency groups throw {@link IllegalStateException} — mirrors
 *       {@link LrcEngine}'s policy.</li>
 *   <li>Groups with zero claim activity in the period (no opening, incurred,
 *       paid, or closing) are skipped — no {@code paa_lic} row written. This
 *       keeps the table free of noise rows that would only contain zeros.</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 * <p>Two layers — DB UNIQUE on {@code uq_paa_lic_group_period} (V36) plus
 * service-layer {@link LicRecognitionAlreadyDoneException} fast-fail. No
 * JE-gateway idempotency layer is needed in v1 because no JE is posted.
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class LicEngine {

    /** Decimal scale matching DECIMAL(18,2) on paa_lic. */
    private static final int MONEY_SCALE = 2;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final GroupOfContractsRepository groupRepository;
    private final PaaLicRepository licRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Recognise LIC roll-forward for {@code periodId} across every group.
     * Idempotent at the (group, period) grain — re-running raises
     * {@link LicRecognitionAlreadyDoneException} for any group already
     * recognised in this period.
     */
    public LicRecognitionResult recognise(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        log.info("LIC recognition starting for period {} ({} → {})",
            periodId, period.getStartDate(), period.getEndDate());

        BigDecimal totalIncurred = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        List<LicRecognitionResult.GroupRecognitionEntry> entries = new ArrayList<>();

        List<GroupOfContracts> groups = groupRepository.findAll().stream()
            .filter(g -> g.getDeletedAt() == null)
            .toList();

        for (GroupOfContracts group : groups) {
            GroupRollForward rollForward = computeRollForward(group, period);

            // Fast-fail idempotency check.
            if (licRepository.findByGroupIdAndPeriodIdAndDeletedAtIsNull(group.getId(), period.getId()).isPresent()) {
                throw new LicRecognitionAlreadyDoneException(period.getId(), group.getId());
            }

            // Skip groups with no activity in this period.
            if (rollForward.allZero()) {
                log.debug("Skipping group {} — no LIC activity in period {}", group.getId(), periodId);
                continue;
            }

            PaaLic licRow = persistRollForward(group, period, rollForward);

            totalIncurred = totalIncurred.add(rollForward.incurred);
            totalPaid = totalPaid.add(rollForward.paid);

            entries.add(new LicRecognitionResult.GroupRecognitionEntry(
                group.getId(),
                licRow.getOpeningBalance(),
                licRow.getClaimsIncurred(),
                licRow.getClaimsPaid(),
                licRow.getClosingBalance()));
        }

        log.info("LIC recognition complete for period {} — {} groups processed, total incurred {}, total paid {}",
            periodId, entries.size(), totalIncurred, totalPaid);

        return new LicRecognitionResult(period.getId(), entries.size(), totalIncurred, totalPaid, entries);
    }

    /**
     * Aggregate claim activity for one group + one period via a single
     * conditional-sum native query. Joins claims to contract_group_assignment
     * on contract_id (filtered to contract_type = 'POLICY') so a group's
     * claims are the union of claims on all direct policies in the group.
     *
     * <p>Date predicates use timestamps because the claims table stores
     * approved_at / settled_at as {@code TIMESTAMPTZ}. period.start_date is
     * a {@code DATE}; we widen it to {@code [period.start 00:00:00,
     * period.end+1 00:00:00)} for half-open interval semantics so an
     * approval at {@code period.end 23:59:59} is counted in this period and
     * one at {@code period.end+1 00:00:00} is counted in the next.
     */
    private GroupRollForward computeRollForward(GroupOfContracts group, FiscalPeriod period) {
        Timestamp periodStartTs = Timestamp.valueOf(LocalDateTime.of(period.getStartDate(), LocalTime.MIDNIGHT));
        Timestamp periodAfterEndTs = Timestamp.valueOf(LocalDateTime.of(period.getEndDate().plusDays(1), LocalTime.MIDNIGHT));

        // Conditional aggregation: one query produces all four columns plus
        // a currency sanity check.
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT " +
            "  COALESCE(SUM(CASE WHEN c.approved_at < ? " +
            "                     AND (c.settled_at IS NULL OR c.settled_at >= ?) " +
            "                    THEN c.approved_amount ELSE 0 END), 0) AS opening_balance, " +
            "  COALESCE(SUM(CASE WHEN c.approved_at >= ? AND c.approved_at < ? " +
            "                    THEN c.approved_amount ELSE 0 END), 0) AS claims_incurred, " +
            "  COALESCE(SUM(CASE WHEN c.settled_at >= ? AND c.settled_at < ? " +
            "                    THEN COALESCE(c.dv_amount, c.approved_amount) ELSE 0 END), 0) AS claims_paid, " +
            "  COALESCE(SUM(CASE WHEN c.approved_at < ? " +
            "                     AND (c.settled_at IS NULL OR c.settled_at >= ?) " +
            "                    THEN c.approved_amount ELSE 0 END), 0) AS closing_balance, " +
            "  MIN(c.currency_code) AS min_ccy, " +
            "  MAX(c.currency_code) AS max_ccy " +
            "FROM claims c " +
            "JOIN contract_group_assignment pga ON pga.contract_id = c.policy_id AND pga.contract_type = 'POLICY' " +
            "WHERE pga.group_id = ? " +
            "  AND c.deleted_at IS NULL " +
            "  AND pga.deleted_at IS NULL " +
            "  AND c.status IN ('APPROVED', 'SETTLED') " +
            "  AND c.approved_at IS NOT NULL " +
            "  AND c.approved_amount IS NOT NULL",
            // opening: approved_at < period.start AND not yet settled at period.start
            periodStartTs, periodStartTs,
            // incurred: approved_at in [period.start, period.end+1)
            periodStartTs, periodAfterEndTs,
            // paid: settled_at in [period.start, period.end+1)
            periodStartTs, periodAfterEndTs,
            // closing: approved_at < period.end+1 AND not yet settled at period.end+1
            periodAfterEndTs, periodAfterEndTs,
            group.getId());

        BigDecimal opening = scale((BigDecimal) row.get("opening_balance"));
        BigDecimal incurred = scale((BigDecimal) row.get("claims_incurred"));
        BigDecimal paid = scale((BigDecimal) row.get("claims_paid"));
        BigDecimal closing = scale((BigDecimal) row.get("closing_balance"));

        String minCcy = (String) row.get("min_ccy");
        String maxCcy = (String) row.get("max_ccy");
        if (minCcy != null && maxCcy != null && !minCcy.equals(maxCcy)) {
            throw new IllegalStateException("Group " + group.getId()
                + " contains claims in multiple currencies (" + minCcy + " and " + maxCcy
                + ") — Slice 2.4 v1 does not support cross-currency aggregation");
        }
        String currency = minCcy == null ? "NGN" : minCcy;

        return new GroupRollForward(opening, incurred, paid, closing, currency);
    }

    private PaaLic persistRollForward(GroupOfContracts group, FiscalPeriod period, GroupRollForward rf) {
        PaaLic lic = new PaaLic();
        lic.setGroup(group);
        lic.setPeriod(period);
        lic.setOpeningBalance(rf.opening);
        lic.setClaimsIncurred(rf.incurred);
        lic.setClaimsPaid(rf.paid);
        // v1 leaves the other movement columns at their entity defaults (zero).
        lic.setClosingBalance(rf.closing);
        lic.setCurrencyCode(rf.currency);
        return licRepository.save(lic);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Aggregated roll-forward values for one (group, period). */
    private record GroupRollForward(
        BigDecimal opening,
        BigDecimal incurred,
        BigDecimal paid,
        BigDecimal closing,
        String currency
    ) {
        boolean allZero() {
            return opening.signum() == 0 && incurred.signum() == 0
                && paid.signum() == 0 && closing.signum() == 0;
        }
    }
}
