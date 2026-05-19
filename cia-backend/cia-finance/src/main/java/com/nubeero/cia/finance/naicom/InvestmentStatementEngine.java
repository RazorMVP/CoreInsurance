package com.nubeero.cia.finance.naicom;

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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NAICOM N08 — Statement of Investments at period end.
 *
 * <p>Module 12 Phase 4 Slice 4.7. A point-in-time portfolio snapshot of
 * the insurer's investment holdings, classified by IFRS 9 category and
 * by asset type. Used by NAICOM to monitor admitted-asset composition
 * and exposure concentration; auditor evidence for the balance-sheet
 * line "Investments".
 *
 * <h2>Substrate: source-table snapshot, not movement analysis</h2>
 * <p>This engine reads {@code investment_holding} LEFT JOIN
 * {@code investment_carrying_value} for the given period. The
 * companion {@link Ifrs9DisclosureEngine} reports period <em>movement</em>
 * over V40; N08 reports the period-end <em>balance</em>:
 * <ul>
 *   <li>All currently {@code ACTIVE} holdings are listed — newly
 *       acquired holdings without a carrying-value row this period
 *       still appear (with null carrying / fair value figures and a
 *       note flagging the missing measurement).</li>
 *   <li>Holdings whose {@code status} is no longer ACTIVE
 *       ({@code MATURED} / {@code SOLD} / {@code IMPAIRED}) are excluded
 *       — the snapshot is "what we hold at period_end", not "what we
 *       held during the period".</li>
 * </ul>
 * <p>The V40 view's WHERE filter excludes holdings without a carrying-
 * value row, which is correct for the §B5.5.39 disclosure but wrong for
 * N08 — hence the direct read instead of going through
 * {@link Ifrs9MovementAnalysisService}.
 *
 * <h2>Aggregation pivots</h2>
 * <p>NAICOM N08 presents the portfolio along three pivots; the engine
 * emits all three:
 * <ul>
 *   <li><b>byHolding</b> — every active holding as one row.</li>
 *   <li><b>byClassification</b> — sums per IFRS 9 classification
 *       (AMORTISED_COST / FVOCI_DEBT / FVOCI_EQUITY / FVPL).</li>
 *   <li><b>byAssetType</b> — sums per asset taxonomy (DEBT / EQUITY /
 *       MONEY_MARKET / DERIVATIVE) — NAICOM's regulatory taxonomy lens.</li>
 *   <li><b>totals</b> — grand totals (acquisition cost, carrying value,
 *       fair value) for the whole portfolio.</li>
 * </ul>
 *
 * <h2>Period semantics</h2>
 * <p>{@code asOf} is always {@code period.endDate}. Carrying value +
 * fair value figures come from the {@code investment_carrying_value} row
 * with {@code period_id = ?} — i.e. period-end measurement. The engine
 * does NOT walk back to find a prior-period measurement if the current
 * period is missing one; doing so would be a v2 enhancement to support
 * the "always show the most-recent measurement" semantics some
 * regulators prefer.
 *
 * <h2>Payload shape</h2>
 * <pre>
 * {
 *   "submissionType": "INVESTMENT_STATEMENT",
 *   "period":         { "id", "start", "end" },
 *   "asOf":           "period.endDate",
 *   "generatedAt":    ISO-8601,
 *   "byHolding": [
 *     {
 *       "holdingId", "isin", "securityName", "issuer",
 *       "assetType", "classification", "status",
 *       "currencyCode", "acquisitionDate", "acquisitionCost",
 *       "faceValue", "couponRate", "maturityDate", "eclStage",
 *       "carryingValue", "fairValue"   // null when no measurement this period
 *     }, ...
 *   ],
 *   "byClassification": [
 *     { "classification", "holdingCount", "acquisitionCost",
 *       "carryingValue", "fairValue" }, ...
 *   ],
 *   "byAssetType": [
 *     { "assetType", "holdingCount", "acquisitionCost",
 *       "carryingValue", "fairValue" }, ...
 *   ],
 *   "totals": {
 *     "holdingCount", "acquisitionCost", "carryingValue", "fairValue"
 *   },
 *   "notes": "v1 scope disclosure"
 * }
 * </pre>
 *
 * <p>Deterministic ordering: byHolding by (classification ASC, security_name
 * ASC); byClassification by classification name ASC; byAssetType by
 * asset_type name ASC.
 *
 * <p>Pure read engine — no DB writes, no JE postings. Orchestrator
 * (Slice 4.9) owns the submission upsert + state machine.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class InvestmentStatementEngine implements NaicomSubmissionEngine {

    private static final int MONEY_SCALE = 2;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public NaicomSubmissionType type() {
        return NaicomSubmissionType.INVESTMENT_STATEMENT;
    }

    @Override
    public Map<String, Object> computePayload(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        List<Map<String, Object>> rows = readActiveHoldings(periodId);

        // Build the byHolding section + accumulators for the three pivots.
        List<Map<String, Object>> holdingRows = new ArrayList<>(rows.size());

        Map<String, AggregateBucket> byClassification = new LinkedHashMap<>();
        Map<String, AggregateBucket> byAssetType = new LinkedHashMap<>();
        BigDecimal grandAcqCost = BigDecimal.ZERO;
        BigDecimal grandCarrying = BigDecimal.ZERO;
        BigDecimal grandFairValue = BigDecimal.ZERO;

        for (Map<String, Object> r : rows) {
            String classification = (String) r.get("classification");
            String assetType = (String) r.get("asset_type");
            BigDecimal acqCost = bd(r.get("acquisition_cost"));
            BigDecimal carrying = (BigDecimal) r.get("closing_balance");
            BigDecimal fairValue = (BigDecimal) r.get("closing_fair_value");

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("holdingId", r.get("id").toString());
            row.put("isin", r.get("isin"));
            row.put("securityName", r.get("security_name"));
            row.put("issuer", r.get("issuer"));
            row.put("assetType", assetType);
            row.put("classification", classification);
            row.put("status", r.get("status"));
            row.put("currencyCode", r.get("currency_code"));
            row.put("acquisitionDate", toIsoDate(r.get("acquisition_date")));
            row.put("acquisitionCost", scale(acqCost));
            row.put("faceValue", r.get("face_value"));
            row.put("couponRate", r.get("coupon_rate"));
            row.put("maturityDate", toIsoDate(r.get("maturity_date")));
            row.put("eclStage", r.get("ecl_stage"));
            row.put("carryingValue", carrying == null ? null : scale(carrying));
            row.put("fairValue", fairValue == null ? null : scale(fairValue));
            holdingRows.add(row);

            byClassification.computeIfAbsent(classification, k -> new AggregateBucket()).add(acqCost, carrying, fairValue);
            byAssetType.computeIfAbsent(assetType, k -> new AggregateBucket()).add(acqCost, carrying, fairValue);
            grandAcqCost = grandAcqCost.add(acqCost);
            if (carrying != null) grandCarrying = grandCarrying.add(carrying);
            if (fairValue != null) grandFairValue = grandFairValue.add(fairValue);
        }

        // Render the byClassification + byAssetType lists with deterministic ordering.
        List<Map<String, Object>> classificationRows = renderBuckets(byClassification, "classification");
        List<Map<String, Object>> assetTypeRows = renderBuckets(byAssetType, "assetType");

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("holdingCount", holdingRows.size());
        totals.put("acquisitionCost", scale(grandAcqCost));
        totals.put("carryingValue", scale(grandCarrying));
        totals.put("fairValue", scale(grandFairValue));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionType", NaicomSubmissionType.INVESTMENT_STATEMENT.name());
        payload.put("period", periodMeta(period));
        payload.put("asOf", period.getEndDate().toString());
        payload.put("generatedAt", Instant.now().toString());
        payload.put("byHolding", holdingRows);
        payload.put("byClassification", classificationRows);
        payload.put("byAssetType", assetTypeRows);
        payload.put("totals", totals);
        payload.put("notes",
            "v1 scope: snapshot lists currently-ACTIVE investment_holding rows "
            + "as of period_end. Carrying value + fair value are the "
            + "investment_carrying_value row for period_id = ? — null when no "
            + "measurement was recorded this period (a holding acquired "
            + "post-close, or a period the engine batch missed). Holdings "
            + "with status MATURED / SOLD / IMPAIRED are excluded — the "
            + "snapshot reports the portfolio still held at period end. "
            + "v2 may walk back to the latest prior measurement when the "
            + "current period is missing one.");

        log.info("Investment Statement (N08) computed as-of {} — {} active holdings; "
                + "acquisition cost {}, carrying value {}, fair value {}",
            period.getEndDate(), holdingRows.size(),
            grandAcqCost, grandCarrying, grandFairValue);

        return payload;
    }

    private List<Map<String, Object>> readActiveHoldings(UUID periodId) {
        // Active holdings + their period-end measurement (LEFT JOIN — a holding
        // without a carrying-value row for this period still appears, with
        // carrying / fair value as null).
        return jdbcTemplate.queryForList(
            "SELECT h.id, h.isin, h.security_name, h.issuer, " +
            "       h.asset_type, h.classification, h.status, h.currency_code, " +
            "       h.acquisition_date, h.acquisition_cost, h.face_value, " +
            "       h.coupon_rate, h.maturity_date, h.ecl_stage, " +
            "       cv.closing_balance, cv.closing_fair_value " +
            "FROM investment_holding h " +
            "LEFT JOIN investment_carrying_value cv " +
            "  ON cv.holding_id = h.id AND cv.period_id = ? AND cv.deleted_at IS NULL " +
            "WHERE h.deleted_at IS NULL " +
            "  AND h.status = 'ACTIVE' " +
            "ORDER BY h.classification, h.security_name",
            periodId);
    }

    private List<Map<String, Object>> renderBuckets(Map<String, AggregateBucket> buckets, String keyName) {
        List<String> ordered = new ArrayList<>(buckets.keySet());
        ordered.sort(String::compareTo);
        List<Map<String, Object>> out = new ArrayList<>(ordered.size());
        for (String key : ordered) {
            AggregateBucket b = buckets.get(key);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(keyName, key);
            row.put("holdingCount", b.holdingCount);
            row.put("acquisitionCost", scale(b.acquisitionCost));
            row.put("carryingValue", scale(b.carryingValue));
            row.put("fairValue", scale(b.fairValue));
            out.add(row);
        }
        return out;
    }

    private static BigDecimal bd(Object o) {
        return o == null ? BigDecimal.ZERO : (BigDecimal) o;
    }

    private static String toIsoDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate ld) return ld.toString();
        if (o instanceof java.sql.Date sql) return sql.toLocalDate().toString();
        return null;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> periodMeta(FiscalPeriod period) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", period.getId().toString());
        meta.put("start", period.getStartDate().toString());
        meta.put("end", period.getEndDate().toString());
        return meta;
    }

    /** Mutable accumulator used during byClassification / byAssetType assembly. */
    private static final class AggregateBucket {
        int holdingCount = 0;
        BigDecimal acquisitionCost = BigDecimal.ZERO;
        BigDecimal carryingValue = BigDecimal.ZERO;
        BigDecimal fairValue = BigDecimal.ZERO;

        void add(BigDecimal acq, BigDecimal carrying, BigDecimal fv) {
            holdingCount++;
            if (acq != null) acquisitionCost = acquisitionCost.add(acq);
            if (carrying != null) carryingValue = carryingValue.add(carrying);
            if (fv != null) fairValue = fairValue.add(fv);
        }
    }
}
