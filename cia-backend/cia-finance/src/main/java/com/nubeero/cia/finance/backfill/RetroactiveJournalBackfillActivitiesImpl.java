package com.nubeero.cia.finance.backfill;

import com.nubeero.cia.common.event.ClaimApprovedEvent;
import com.nubeero.cia.common.event.ClaimExpenseApprovedEvent;
import com.nubeero.cia.common.event.ClaimSettledEvent;
import com.nubeero.cia.common.event.EndorsementApprovedEvent;
import com.nubeero.cia.common.event.FacPremiumCededEvent;
import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.finance.gl.JournalEntryDuplicateException;
import com.nubeero.cia.finance.gl.LockReportEntry;
import com.nubeero.cia.finance.gl.PeriodLockService;
import com.nubeero.cia.finance.gl.SubledgerPostingService;
import com.nubeero.cia.workflow.backfill.BackfillChunkRequest;
import com.nubeero.cia.workflow.backfill.BackfillChunkResult;
import com.nubeero.cia.workflow.backfill.BackfillPreflightResult;
import com.nubeero.cia.workflow.backfill.RetroactiveJournalBackfillActivities;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of the retroactive JE backfill activities (Slice 1.8a).
 *
 * <p>Lives in {@code cia-finance} because the impl must call
 * {@link SubledgerPostingService} (the same replay path the live event
 * listeners drive) and use the tenant-routed Hibernate {@code EntityManager}
 * for native queries against tables owned by sibling modules
 * ({@code policies}, {@code claims}, {@code endorsements},
 * {@code claim_expenses}, {@code ri_fac_covers}).
 *
 * <h2>Why native SQL and not JPA</h2>
 * <p>Using JPA entities would force {@code cia-finance} to depend on
 * {@code cia-policy}, {@code cia-claims}, {@code cia-endorsement}, and
 * {@code cia-reinsurance} — inverting the modular layering and producing
 * cycle risks. Native queries via {@link EntityManager#createNativeQuery}
 * still route through the {@code MultiTenantConnectionProvider} so they
 * land in the correct tenant schema; only the column-to-field mapping
 * happens here in code.
 *
 * <h2>Idempotency</h2>
 * <p>Replay rows whose {@code (sourceModule, sourceEventType,
 * sourceReference)} triple already exists raise
 * {@link JournalEntryDuplicateException} — caught and counted as
 * {@code alreadyExists}. This is the canonical "replay is safe" signal: a
 * second run of the workflow over the same date range is a no-op for any
 * row that posted successfully the first time.
 *
 * <h2>Per-row error isolation</h2>
 * <p>An unexpected exception on row N (e.g. {@code InactiveAccountException}
 * because a historical COA code has since been decommissioned) is logged,
 * counted as {@code failed}, and the loop continues. The activity always
 * returns a successful chunk result with structured counts so the workflow
 * can aggregate. Without this, Temporal would retry the whole chunk
 * forever as long as one row is poisoned.
 *
 * <h2>Heartbeats</h2>
 * <p>{@link ActivityExecutionContext#heartbeat} every 10 rows tells the
 * Temporal server the activity is alive — required for chunks that run
 * longer than the configured heartbeat timeout. The payload is the current
 * within-chunk index so a future iteration can resume from there, but
 * Slice 1.8a's retry semantics restart the chunk and rely on idempotency
 * instead.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetroactiveJournalBackfillActivitiesImpl implements RetroactiveJournalBackfillActivities {

    private static final int HEARTBEAT_EVERY_N_ROWS = 10;

    @PersistenceContext
    private EntityManager em;

    private final SubledgerPostingService subledgerPostingService;
    private final PeriodLockService periodLockService;

    @Override
    public BackfillPreflightResult previewPeriodLocks(String tenantId, LocalDate fromDate, LocalDate toDate) {
        try {
            TenantContext.setTenantId(tenantId);
            List<LockReportEntry> snapshot = periodLockService.previewLock(fromDate, toDate);
            List<String> blocking = snapshot.stream()
                    .filter(e -> e.rejected() || e.requiresOverride())
                    .map(LockReportEntry::periodLabel)
                    .distinct()
                    .collect(Collectors.toList());
            if (blocking.isEmpty()) {
                return new BackfillPreflightResult(false, List.of(),
                        "All %d days in range are writable".formatted(snapshot.size()));
            }
            String summary = "Range crosses %d locked period(s): %s — reopen or narrow range before retrying"
                    .formatted(blocking.size(), String.join(", ", blocking));
            log.warn("Backfill preflight refused for tenant {}: {}", tenantId, summary);
            return new BackfillPreflightResult(true, blocking, summary);
        } finally {
            // Cleanup also runs via TenantAwareWorkerInterceptor.finally, but
            // explicit clear here defends against caller threads that aren't
            // worker pool threads (e.g. unit tests).
            TenantContext.clear();
        }
    }

    @Override
    public BackfillChunkResult processChunk(BackfillChunkRequest request) {
        TenantContext.setTenantId(request.tenantId());
        ActivityExecutionContext ctx = activityContext();
        try {
            return switch (request.eventType()) {
                case POLICY_APPROVED         -> processPolicyApproved(request, ctx);
                case CLAIM_APPROVED          -> processClaimApproved(request, ctx);
                case CLAIM_SETTLED           -> processClaimSettled(request, ctx);
                case CLAIM_EXPENSE_APPROVED  -> processClaimExpenseApproved(request, ctx);
                case ENDORSEMENT_APPROVED    -> processEndorsementApproved(request, ctx);
                case FAC_PREMIUM_CEDED       -> processFacPremiumCeded(request, ctx);
            };
        } finally {
            TenantContext.clear();
        }
    }

    /** Returns the Temporal activity context, or {@code null} for unit-test invocations. */
    private static ActivityExecutionContext activityContext() {
        try {
            return Activity.getExecutionContext();
        } catch (IllegalStateException ex) {
            // Not running inside a Temporal worker (unit test). Heartbeats become no-ops.
            return null;
        }
    }

    private static void heartbeat(ActivityExecutionContext ctx, int index) {
        if (ctx != null && index % HEARTBEAT_EVERY_N_ROWS == 0) {
            ctx.heartbeat(index);
        }
    }

    // ─── POLICY_APPROVED ──────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private BackfillChunkResult processPolicyApproved(BackfillChunkRequest req, ActivityExecutionContext ctx) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, policy_number, customer_id, customer_name,
                       broker_id, broker_name, product_id, product_name,
                       net_premium, currency_code,
                       policy_start_date, policy_end_date,
                       total_sum_insured, class_of_business_id
                  FROM policies
                 WHERE status = 'APPROVED'
                   AND deleted_at IS NULL
                   AND policy_start_date BETWEEN :from AND :to
                 ORDER BY policy_start_date, id
                 LIMIT :limit OFFSET :offset
                """)
                .setParameter("from", req.fromDate())
                .setParameter("to", req.toDate())
                .setParameter("limit", req.limit())
                .setParameter("offset", req.offset())
                .getResultList();

        long attempted = 0, posted = 0, alreadyExists = 0, failed = 0;
        int index = 0;
        for (Object[] row : rows) {
            attempted++;
            try {
                PolicyApprovedEvent event = new PolicyApprovedEvent(
                        uuid(row[0]),
                        str(row[1]),
                        uuid(row[2]),
                        str(row[3]),
                        uuid(row[4]),
                        str(row[5]),
                        str(row[7]),
                        bd(row[8]),
                        defaultCurrency(str(row[9])),
                        date(row[11]),
                        uuid(row[6]),
                        uuid(row[13]),
                        bd(row[12]),
                        date(row[10]));
                if (req.dryRun()) {
                    posted++;
                } else {
                    subledgerPostingService.replayPolicyApproved(event);
                    posted++;
                }
            } catch (JournalEntryDuplicateException dup) {
                alreadyExists++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Backfill POLICY_APPROVED failed for row {}: {}", row[0], ex.getMessage());
            }
            heartbeat(ctx, ++index);
        }
        return new BackfillChunkResult(attempted, posted, alreadyExists, failed, rows.size() < req.limit());
    }

    // ─── CLAIM_APPROVED ───────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private BackfillChunkResult processClaimApproved(BackfillChunkRequest req, ActivityExecutionContext ctx) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, claim_number, policy_id, policy_number,
                       customer_id, customer_name, broker_id, broker_name,
                       product_name, approved_amount, currency_code, approved_at
                  FROM claims
                 WHERE status IN ('APPROVED','SETTLED')
                   AND deleted_at IS NULL
                   AND approved_amount IS NOT NULL
                   AND approved_at IS NOT NULL
                   AND (approved_at AT TIME ZONE 'UTC')::date BETWEEN :from AND :to
                 ORDER BY approved_at, id
                 LIMIT :limit OFFSET :offset
                """)
                .setParameter("from", req.fromDate())
                .setParameter("to", req.toDate())
                .setParameter("limit", req.limit())
                .setParameter("offset", req.offset())
                .getResultList();

        long attempted = 0, posted = 0, alreadyExists = 0, failed = 0;
        int index = 0;
        for (Object[] row : rows) {
            attempted++;
            try {
                ClaimApprovedEvent event = new ClaimApprovedEvent(
                        uuid(row[0]),
                        str(row[1]),
                        uuid(row[2]),
                        str(row[3]),
                        uuid(row[4]),
                        str(row[5]),
                        uuid(row[6]),
                        str(row[7]),
                        str(row[8]),
                        bd(row[9]),
                        defaultCurrency(str(row[10])));
                LocalDate businessDate = instantToDate(row[11]);
                if (req.dryRun()) {
                    posted++;
                } else {
                    subledgerPostingService.replayClaimApproved(event, businessDate);
                    posted++;
                }
            } catch (JournalEntryDuplicateException dup) {
                alreadyExists++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Backfill CLAIM_APPROVED failed for row {}: {}", row[0], ex.getMessage());
            }
            heartbeat(ctx, ++index);
        }
        return new BackfillChunkResult(attempted, posted, alreadyExists, failed, rows.size() < req.limit());
    }

    // ─── CLAIM_SETTLED ────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private BackfillChunkResult processClaimSettled(BackfillChunkRequest req, ActivityExecutionContext ctx) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, claim_number, policy_id, policy_number,
                       customer_id, customer_name, approved_amount,
                       currency_code, settled_at
                  FROM claims
                 WHERE status = 'SETTLED'
                   AND deleted_at IS NULL
                   AND settled_at IS NOT NULL
                   AND (settled_at AT TIME ZONE 'UTC')::date BETWEEN :from AND :to
                 ORDER BY settled_at, id
                 LIMIT :limit OFFSET :offset
                """)
                .setParameter("from", req.fromDate())
                .setParameter("to", req.toDate())
                .setParameter("limit", req.limit())
                .setParameter("offset", req.offset())
                .getResultList();

        long attempted = 0, posted = 0, alreadyExists = 0, failed = 0;
        int index = 0;
        for (Object[] row : rows) {
            attempted++;
            try {
                Instant settledAt = instant(row[8]);
                BigDecimal settledAmount = bd(row[6]);
                if (settledAt == null || settledAmount == null) {
                    // Defensive — the WHERE clause filters these, but guard anyway.
                    failed++;
                    continue;
                }
                ClaimSettledEvent event = new ClaimSettledEvent(
                        uuid(row[0]),
                        str(row[1]),
                        uuid(row[2]),
                        str(row[3]),
                        uuid(row[4]),
                        str(row[5]),
                        settledAmount,
                        defaultCurrency(str(row[7])),
                        settledAt);
                if (req.dryRun()) {
                    posted++;
                } else {
                    subledgerPostingService.replayClaimSettled(event);
                    posted++;
                }
            } catch (JournalEntryDuplicateException dup) {
                alreadyExists++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Backfill CLAIM_SETTLED failed for row {}: {}", row[0], ex.getMessage());
            }
            heartbeat(ctx, ++index);
        }
        return new BackfillChunkResult(attempted, posted, alreadyExists, failed, rows.size() < req.limit());
    }

    // ─── CLAIM_EXPENSE_APPROVED ───────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private BackfillChunkResult processClaimExpenseApproved(BackfillChunkRequest req, ActivityExecutionContext ctx) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT e.id, e.claim_id, c.claim_number,
                       e.vendor_id, e.vendor_name, e.expense_type,
                       e.amount, c.currency_code, e.approved_at
                  FROM claim_expenses e
                  JOIN claims c ON c.id = e.claim_id
                 WHERE e.status = 'APPROVED'
                   AND e.deleted_at IS NULL
                   AND e.approved_at IS NOT NULL
                   AND (e.approved_at AT TIME ZONE 'UTC')::date BETWEEN :from AND :to
                 ORDER BY e.approved_at, e.id
                 LIMIT :limit OFFSET :offset
                """)
                .setParameter("from", req.fromDate())
                .setParameter("to", req.toDate())
                .setParameter("limit", req.limit())
                .setParameter("offset", req.offset())
                .getResultList();

        long attempted = 0, posted = 0, alreadyExists = 0, failed = 0;
        int index = 0;
        for (Object[] row : rows) {
            attempted++;
            try {
                UUID expenseId = uuid(row[0]);
                ClaimExpenseApprovedEvent event = new ClaimExpenseApprovedEvent(
                        expenseId,
                        expenseId.toString(),   // use id as the reference for idempotency triple slot 3
                        uuid(row[1]),
                        str(row[2]),
                        uuid(row[3]),
                        str(row[4]),
                        str(row[5]),
                        bd(row[6]),
                        defaultCurrency(str(row[7])));
                LocalDate businessDate = instantToDate(row[8]);
                if (req.dryRun()) {
                    posted++;
                } else {
                    subledgerPostingService.replayClaimExpenseApproved(event, businessDate);
                    posted++;
                }
            } catch (JournalEntryDuplicateException dup) {
                alreadyExists++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Backfill CLAIM_EXPENSE_APPROVED failed for row {}: {}", row[0], ex.getMessage());
            }
            heartbeat(ctx, ++index);
        }
        return new BackfillChunkResult(attempted, posted, alreadyExists, failed, rows.size() < req.limit());
    }

    // ─── ENDORSEMENT_APPROVED ─────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private BackfillChunkResult processEndorsementApproved(BackfillChunkRequest req, ActivityExecutionContext ctx) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, endorsement_number, policy_id, policy_number,
                       customer_id, customer_name, broker_id, broker_name,
                       product_name, premium_adjustment, currency_code, approved_at
                  FROM endorsements
                 WHERE status = 'APPROVED'
                   AND deleted_at IS NULL
                   AND premium_adjustment <> 0
                   AND approved_at IS NOT NULL
                   AND (approved_at AT TIME ZONE 'UTC')::date BETWEEN :from AND :to
                 ORDER BY approved_at, id
                 LIMIT :limit OFFSET :offset
                """)
                .setParameter("from", req.fromDate())
                .setParameter("to", req.toDate())
                .setParameter("limit", req.limit())
                .setParameter("offset", req.offset())
                .getResultList();

        long attempted = 0, posted = 0, alreadyExists = 0, failed = 0;
        int index = 0;
        for (Object[] row : rows) {
            attempted++;
            try {
                EndorsementApprovedEvent event = new EndorsementApprovedEvent(
                        uuid(row[0]),
                        str(row[1]),
                        uuid(row[2]),
                        str(row[3]),
                        uuid(row[4]),
                        str(row[5]),
                        uuid(row[6]),
                        str(row[7]),
                        str(row[8]),
                        bd(row[9]),
                        defaultCurrency(str(row[10])));
                LocalDate businessDate = instantToDate(row[11]);
                if (req.dryRun()) {
                    posted++;
                } else {
                    subledgerPostingService.replayEndorsementApproved(event, businessDate);
                    posted++;
                }
            } catch (JournalEntryDuplicateException dup) {
                alreadyExists++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Backfill ENDORSEMENT_APPROVED failed for row {}: {}", row[0], ex.getMessage());
            }
            heartbeat(ctx, ++index);
        }
        return new BackfillChunkResult(attempted, posted, alreadyExists, failed, rows.size() < req.limit());
    }

    // ─── FAC_PREMIUM_CEDED ────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private BackfillChunkResult processFacPremiumCeded(BackfillChunkRequest req, ActivityExecutionContext ctx) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, fac_reference, policy_id, policy_number,
                       reinsurance_company_id, reinsurance_company_name,
                       premium_ceded, commission_amount, net_premium,
                       currency_code, approved_at
                  FROM ri_fac_covers
                 WHERE status = 'APPROVED'
                   AND deleted_at IS NULL
                   AND approved_at IS NOT NULL
                   AND (approved_at AT TIME ZONE 'UTC')::date BETWEEN :from AND :to
                 ORDER BY approved_at, id
                 LIMIT :limit OFFSET :offset
                """)
                .setParameter("from", req.fromDate())
                .setParameter("to", req.toDate())
                .setParameter("limit", req.limit())
                .setParameter("offset", req.offset())
                .getResultList();

        long attempted = 0, posted = 0, alreadyExists = 0, failed = 0;
        int index = 0;
        for (Object[] row : rows) {
            attempted++;
            try {
                FacPremiumCededEvent event = new FacPremiumCededEvent(
                        uuid(row[0]),
                        str(row[1]),
                        uuid(row[2]),
                        str(row[3]),
                        uuid(row[4]),
                        str(row[5]),
                        bd(row[6]),
                        bd(row[7]),
                        bd(row[8]),
                        defaultCurrency(str(row[9])));
                LocalDate businessDate = instantToDate(row[10]);
                if (req.dryRun()) {
                    posted++;
                } else {
                    subledgerPostingService.replayFacPremiumCeded(event, businessDate);
                    posted++;
                }
            } catch (JournalEntryDuplicateException dup) {
                alreadyExists++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Backfill FAC_PREMIUM_CEDED failed for row {}: {}", row[0], ex.getMessage());
            }
            heartbeat(ctx, ++index);
        }
        return new BackfillChunkResult(attempted, posted, alreadyExists, failed, rows.size() < req.limit());
    }

    // ─── Native-row coercion helpers ──────────────────────────────────────────
    //
    // PostgreSQL JDBC returns column values as java.util.UUID for UUID,
    // java.math.BigDecimal for NUMERIC, java.sql.Date for DATE,
    // java.sql.Timestamp / java.time.OffsetDateTime / java.time.Instant for
    // TIMESTAMPTZ depending on driver version. The helpers absorb those
    // variants and produce the precise types each event record expects so
    // the row → event mapping above stays declarative.

    private static UUID uuid(Object v) {
        return v == null ? null : (v instanceof UUID u ? u : UUID.fromString(v.toString()));
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static BigDecimal bd(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        return new BigDecimal(v.toString());
    }

    private static LocalDate date(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof Date d) return d.toLocalDate();
        return LocalDate.parse(v.toString());
    }

    private static Instant instant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof Timestamp t) return t.toInstant();
        if (v instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return Instant.parse(v.toString());
    }

    private static LocalDate instantToDate(Object v) {
        Instant i = instant(v);
        return i == null ? null : i.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }

    private static String defaultCurrency(String code) {
        return code == null || code.isBlank() ? "NGN" : code;
    }
}
