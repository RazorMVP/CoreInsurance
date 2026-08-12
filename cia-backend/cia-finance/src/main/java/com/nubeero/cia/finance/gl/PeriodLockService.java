package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.entity.LockableByPeriod;
import com.nubeero.cia.common.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Period-close lock orchestrator — the single authoritative path for:
 * <ul>
 *   <li>{@code softClose / hardClose / reopen} — lifecycle transitions on a
 *       {@link FiscalPeriod}.</li>
 *   <li>{@link #checkWrite(LockableByPeriod)} — the per-flush decision the
 *       {@link PeriodLockInterceptor} delegates to.</li>
 *   <li>{@link #previewLock(LocalDate, LocalDate)} — bulk preview so callers
 *       (Module 8 bulk receipts, Slice 1.8 backfill, bordereaux) can
 *       pre-check a date range before the workflow starts, instead of
 *       discovering the lock on row 4,837 of 10,000.</li>
 * </ul>
 *
 * <h2>Grace-window calculation</h2>
 * <p>5 business days (Mon–Fri, weekends skipped, no holiday calendar in v1
 * per design decision D4=B). Computed at soft-close time and stored as
 * {@code period_lock.grace_window_until} so different period types or
 * year-ends can carry different grace lengths in future without a schema
 * change — the value is loaded, not recomputed, on every check.
 *
 * <h2>Concurrency</h2>
 * <p>Soft-close / hard-close acquire a row-level lock via the SELECT in
 * {@link PeriodLockRepository#findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull}
 * followed by an UPDATE in the same transaction. Two concurrent close
 * attempts on the same period serialise; the loser sees an already-active
 * lock and either no-ops (idempotent close) or fails the constraint check.
 *
 * <h2>Audit trail</h2>
 * <p>Every lifecycle transition writes an {@code audit_log} row via
 * {@link AuditService}: action {@code CLOSE} for soft/hard, {@code REOPEN}
 * for release. The {@code period_lock} rows themselves are the
 * authoritative history; audit rows give the cross-system view (who, when,
 * from where, via what JWT session).
 *
 * @since Module 12, Slice 1.7
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PeriodLockService {

    /** Grace window length for v1 — 5 business days. */
    static final int DEFAULT_GRACE_BUSINESS_DAYS = 5;

    /** Keycloak role granting soft-close grace-window override. */
    public static final String ROLE_OVERRIDE_LOCK = "ROLE_FINANCE_OVERRIDE_LOCK";

    /** Keycloak role granting reopen of HARD-closed periods. */
    public static final String ROLE_REOPEN_PERIOD = "ROLE_FINANCE_REOPEN_PERIOD";

    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter YEAR_LABEL = DateTimeFormatter.ofPattern("yyyy", Locale.ENGLISH);

    private final FiscalPeriodRepository periodRepository;
    private final PeriodLockRepository lockRepository;
    private final FiscalPeriodResolver periodResolver;
    private final FiscalPeriodLookupCache lookupCache;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    // Slice 1.7c — optional holiday repository. Constructor-injected by
    // Spring; null is tolerated so existing tests (and pre-V35 schemas)
    // continue to work without forcing every wiring path to provide one.
    private final TenantHolidayRepository tenantHolidayRepository;

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Soft-close a period. Idempotent — if an active SOFT lock already
     * exists, returns it without modification. Rejects if the period is
     * already HARD-locked (must reopen first).
     */
    public PeriodLock softClose(UUID periodId, String reason) {
        FiscalPeriod period = mustFindPeriod(periodId);
        Optional<PeriodLock> active = lockRepository.findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(periodId);

        if (active.isPresent()) {
            PeriodLock existing = active.get();
            if (existing.getLockType() == LockType.SOFT) return existing;       // idempotent
            throw new BusinessRuleException("PERIOD_ALREADY_HARD_CLOSED",
                "Period %s is HARD-closed; reopen before soft-closing again".formatted(labelFor(period)));
        }

        Instant now = Instant.now();
        PeriodLock lock = new PeriodLock();
        lock.setFiscalPeriodId(periodId);
        lock.setLockType(LockType.SOFT);
        lock.setLockedAt(now);
        lock.setLockedBy(currentUser());
        // Slice 1.7c — production close uses the holiday-aware instance
        // method; the static overload is kept only for back-compat with the
        // pre-1.7c unit tests that don't have a TenantHolidayRepository.
        lock.setGraceWindowUntil(addBusinessDaysWithHolidays(now, DEFAULT_GRACE_BUSINESS_DAYS));
        PeriodLock saved = lockRepository.save(lock);

        period.setStatus(FiscalPeriodStatus.SOFT_CLOSED);
        period.setSoftClosedAt(now);
        periodRepository.save(period);

        auditService.log("FiscalPeriod", periodId.toString(), AuditAction.CLOSE,
            FiscalPeriodStatus.OPEN, lockSummary(saved, reason));
        log.info("Period {} soft-closed; grace ends at {}", labelFor(period), saved.getGraceWindowUntil());
        return saved;
    }

    /**
     * Hard-close a period. Requires an existing SOFT lock (or else
     * {@link #softClose} is called transparently first to honour the
     * V31 {@code ck_fiscal_period_close_chronology} constraint:
     * {@code hard_closed_at >= soft_closed_at}).
     */
    public PeriodLock hardClose(UUID periodId, String reason) {
        FiscalPeriod period = mustFindPeriod(periodId);
        Optional<PeriodLock> active = lockRepository.findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(periodId);

        if (active.isPresent() && active.get().getLockType() == LockType.HARD) return active.get();   // idempotent

        // Transition: ensure SOFT exists first. If it doesn't, soft-close in the same TX —
        // the V31 chronology CHECK requires soft_closed_at < hard_closed_at, both NOT NULL.
        if (active.isEmpty()) {
            softClose(periodId, "auto-soft prior to hard close: " + reason);
        } else if (active.get().getLockType() == LockType.SOFT) {
            // Release the SOFT lock — period_lock.released_at is set so the new HARD row is
            // the unique active lock. The released SOFT row remains as history.
            releaseLock(active.get(), "promoted to HARD: " + reason);
        }

        Instant now = Instant.now();
        PeriodLock lock = new PeriodLock();
        lock.setFiscalPeriodId(periodId);
        lock.setLockType(LockType.HARD);
        lock.setLockedAt(now);
        lock.setLockedBy(currentUser());
        // HARD locks have no grace window — only a reopen lifts them.
        lock.setGraceWindowUntil(null);
        PeriodLock saved = lockRepository.save(lock);

        period.setStatus(FiscalPeriodStatus.HARD_CLOSED);
        period.setHardClosedAt(now);
        periodRepository.save(period);

        auditService.log("FiscalPeriod", periodId.toString(), AuditAction.CLOSE,
            FiscalPeriodStatus.SOFT_CLOSED, lockSummary(saved, reason));
        log.info("Period {} hard-closed", labelFor(period));
        return saved;
    }

    /**
     * Release a HARD lock so the period accepts writes again. Status flips
     * to REOPENED and a {@link PeriodReopenedEvent} is published — the
     * downstream listener fires the configured CFO + compliance email
     * notification.
     */
    public PeriodLock reopen(UUID periodId, String reason) {
        FiscalPeriod period = mustFindPeriod(periodId);
        PeriodLock active = lockRepository.findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(periodId)
            .orElseThrow(() -> new BusinessRuleException("PERIOD_NOT_LOCKED",
                "Period %s has no active lock to reopen".formatted(labelFor(period))));
        if (active.getLockType() != LockType.HARD) {
            throw new BusinessRuleException("PERIOD_NOT_HARD_CLOSED",
                "Reopen is valid only for HARD-closed periods; %s is %s".formatted(labelFor(period), active.getLockType()));
        }
        releaseLock(active, reason);

        period.setStatus(FiscalPeriodStatus.REOPENED);
        period.setHardClosedAt(null);   // cache invariant: REOPENED ⇒ no active hard close timestamp
        periodRepository.save(period);

        auditService.log("FiscalPeriod", periodId.toString(), AuditAction.REOPEN,
            FiscalPeriodStatus.HARD_CLOSED, FiscalPeriodStatus.REOPENED);
        events.publishEvent(new PeriodReopenedEvent(this, periodId, labelFor(period), currentUser(), reason));
        log.info("Period {} reopened by {}", labelFor(period), currentUser());
        return active;
    }

    private void releaseLock(PeriodLock lock, String reason) {
        lock.setReleasedAt(Instant.now());
        lock.setReleasedBy(currentUser());
        lock.setReleaseReason(reason);
        lockRepository.save(lock);
    }

    /**
     * Up-front guard for callers that must fail <strong>before doing any
     * work</strong> if the target period isn't OPEN — as opposed to
     * {@link #checkWrite}, which is a per-entity decision invoked at
     * Hibernate flush time (via {@link PeriodLockInterceptor}) and so only
     * fires once a write is already in flight.
     *
     * <p>{@code FacPaaCutoverService.runCutover} (FAC / IFRS-17 PAA
     * workstream Task 5) calls this first, before enumerating or grouping
     * any contract, so a modified-prospective cutover against a closed
     * period throws {@link PeriodLockedException} with zero side effects —
     * including in test harnesses that never wire {@link
     * PeriodLockInterceptor} into the {@code EntityManagerFactory}.
     *
     * <p>Rejects on ANY active lock (SOFT or HARD) — unlike {@code
     * checkWrite}, there is no soft-close grace-window / override carve-out
     * here; a bulk one-time cutover run always requires a fully OPEN (or
     * REOPENED) period.
     *
     * @throws PeriodLockedException if the period carries an active lock
     */
    @Transactional(readOnly = true)
    public void assertOpenForPosting(UUID periodId) {
        FiscalPeriod period = mustFindPeriod(periodId);
        Optional<PeriodLock> active = lockRepository.findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(periodId);
        if (active.isPresent()) {
            PeriodLock lock = active.get();
            throw new PeriodLockedException(LockDecision.reject(
                period.getId(), labelFor(period), period.getStatus(), lock.getGraceWindowUntil(),
                List.of(), "Period is " + lock.getLockType() + "-closed — this operation requires an OPEN period"));
        }
    }

    // ─── Read paths ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PeriodLock> history(UUID periodId) {
        return lockRepository.findByFiscalPeriodIdAndDeletedAtIsNullOrderByLockedAtDesc(periodId);
    }

    /**
     * Bulk preview — returns the lock status for every business date in the
     * inclusive range. Callers planning a bulk operation (e.g. backfilling
     * 90 days of JEs) get one snapshot, not per-row surprises.
     */
    @Transactional(readOnly = true)
    public List<LockReportEntry> previewLock(LocalDate fromDate, LocalDate toDate) {
        if (toDate.isBefore(fromDate)) {
            throw new BusinessRuleException("INVALID_DATE_RANGE",
                "toDate %s precedes fromDate %s".formatted(toDate, fromDate));
        }
        List<LockReportEntry> rows = new ArrayList<>();
        LocalDate cursor = fromDate;
        while (!cursor.isAfter(toDate)) {
            rows.add(buildReportEntry(cursor));
            cursor = cursor.plusDays(1);
        }
        return rows;
    }

    private LockReportEntry buildReportEntry(LocalDate date) {
        try {
            FiscalPeriod period = periodResolver.resolveMonthForBusinessDate(date);
            Optional<PeriodLock> active = lockRepository.findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(period.getId());
            boolean requiresOverride = active.map(this::requiresOverrideAt).orElse(false);
            boolean rejected = active.map(l -> l.getLockType() == LockType.HARD).orElse(false);
            return new LockReportEntry(date, period.getId(), labelFor(period), period.getStatus(),
                active.map(PeriodLock::getGraceWindowUntil).orElse(null), requiresOverride, rejected);
        } catch (FiscalPeriodNotFoundException ex) {
            return new LockReportEntry(date, null, "(no period)", null, null, false, true);
        }
    }

    private boolean requiresOverrideAt(PeriodLock lock) {
        if (lock.getLockType() == LockType.HARD) return false;   // hard = outright reject, not override-able
        Instant grace = lock.getGraceWindowUntil();
        return grace != null && Instant.now().isAfter(grace);
    }

    // ─── checkWrite — interceptor entrypoint ──────────────────────────────────

    /**
     * The hot path. Invoked once per persisted {@link LockableByPeriod}
     * entity per flush. Returns the structured {@link LockDecision} the
     * interceptor turns into {@link PeriodLockedException} or an audit-log
     * override row.
     */
    @Transactional(readOnly = true)
    public LockDecision checkWrite(LockableByPeriod entity) {
        if (entity == null || entity.getLockDate() == null) return LockDecision.allow();
        if (entity.isReversal()) return LockDecision.allow();   // reversal carve-out

        LocalDate lockDate = entity.getLockDate();
        Optional<FiscalPeriodLookupCache.PeriodSnapshot> snap = lookupCache.get(lockDate, this::loadSnapshot);
        if (snap.isEmpty()) {
            return LockDecision.reject(null, "(no period)", null, null, List.of(),
                "No fiscal period defined for date " + lockDate);
        }

        FiscalPeriodLookupCache.PeriodSnapshot s = snap.get();
        Optional<FiscalPeriodLookupCache.ActiveLock> activeOpt = s.activeLock();
        if (activeOpt.isEmpty()) return LockDecision.allow(s.periodId(), s.periodLabel(), s.status());

        FiscalPeriodLookupCache.ActiveLock lock = activeOpt.get();
        if (lock.lockType() == LockType.HARD) {
            return LockDecision.reject(s.periodId(), s.periodLabel(), s.status(), null,
                List.of(),   // no override exists for HARD past initial close — must reopen first
                "Period is HARD-closed; reopen required");
        }

        // SOFT lock from here on.
        Instant graceEnd = lock.graceWindowUntil();
        boolean withinGrace = graceEnd == null || Instant.now().isBefore(graceEnd);
        if (withinGrace) return LockDecision.allow(s.periodId(), s.periodLabel(), s.status());

        if (hasRole(ROLE_OVERRIDE_LOCK)) {
            return LockDecision.override(s.periodId(), s.periodLabel(), s.status(), graceEnd);
        }
        return LockDecision.reject(s.periodId(), s.periodLabel(), s.status(), graceEnd,
            List.of("FINANCE_OVERRIDE_LOCK"),
            "SOFT-close grace window ended at " + graceEnd);
    }

    private Optional<FiscalPeriodLookupCache.PeriodSnapshot> loadSnapshot(LocalDate date) {
        try {
            FiscalPeriod period = periodResolver.resolveMonthForBusinessDate(date);
            Optional<PeriodLock> active = lockRepository.findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(period.getId());
            Optional<FiscalPeriodLookupCache.ActiveLock> activeSnap = active.map(l ->
                new FiscalPeriodLookupCache.ActiveLock(l.getLockType(), l.getLockedAt(), l.getGraceWindowUntil()));
            return Optional.of(new FiscalPeriodLookupCache.PeriodSnapshot(
                period.getId(), labelFor(period), period.getStatus(),
                period.getStartDate(), period.getEndDate(), activeSnap));
        } catch (FiscalPeriodNotFoundException ex) {
            return Optional.empty();
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Slice 1.7 back-compat — weekends-only addition (no holidays).
     * Kept as a static convenience for existing unit tests; the production
     * close-flow uses {@link #addBusinessDaysWithHolidays} which consults
     * the tenant_holiday table.
     */
    static Instant addBusinessDays(Instant from, int days) {
        return addBusinessDays(from, days, java.util.Set.of());
    }

    /**
     * Slice 1.7c — holiday-aware overload. Skips weekends AND any date in
     * {@code holidays}. Static + parameterised so unit tests can fix the
     * holiday set without spinning up the repository.
     */
    static Instant addBusinessDays(Instant from, int days, java.util.Set<LocalDate> holidays) {
        LocalDate date = from.atOffset(ZoneOffset.UTC).toLocalDate();
        int added = 0;
        while (added < days) {
            date = date.plusDays(1);
            DayOfWeek dow = date.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) continue;
            if (holidays.contains(date)) continue;
            added++;
        }
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Instance-level entry point used by softClose — loads holidays from
     * {@code tenant_holiday} and delegates to the static overload. Falls
     * back to weekends-only when the repository isn't wired (legacy tests).
     */
    Instant addBusinessDaysWithHolidays(Instant from, int days) {
        return addBusinessDays(from, days, loadHolidays());
    }

    private java.util.Set<LocalDate> loadHolidays() {
        if (tenantHolidayRepository == null) return java.util.Set.of();
        return tenantHolidayRepository.findAllByDeletedAtIsNullOrderByHolidayDateAsc().stream()
            .map(TenantHoliday::getHolidayDate)
            .collect(java.util.stream.Collectors.toSet());
    }

    /** Days since the soft-close timestamp, in business days. Public helper used by previewer + frontend. */
    public int daysSinceSoftClose(FiscalPeriod period, LocalDate today) {
        if (period.getSoftClosedAt() == null) return 0;
        LocalDate softCloseDate = period.getSoftClosedAt().atOffset(ZoneOffset.UTC).toLocalDate();
        if (today.isBefore(softCloseDate)) return 0;
        int days = 0;
        LocalDate cursor = softCloseDate;
        while (cursor.isBefore(today)) {
            cursor = cursor.plusDays(1);
            DayOfWeek dow = cursor.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) days++;
        }
        return days;
    }

    private FiscalPeriod mustFindPeriod(UUID periodId) {
        return periodRepository.findById(periodId)
            .orElseThrow(() -> new BusinessRuleException("PERIOD_NOT_FOUND",
                "Fiscal period not found: " + periodId));
    }

    /** Renders a period as "May 2026" / "Q2 2026" / "2026" / start–end date for DAY. */
    String labelFor(FiscalPeriod p) {
        return switch (p.getPeriodType()) {
            case MONTH -> p.getStartDate().format(MONTH_LABEL);
            case QUARTER -> "Q%d %s".formatted(((p.getStartDate().getMonthValue() - 1) / 3) + 1, p.getStartDate().format(YEAR_LABEL));
            case HALF_YEAR -> "H%d %s".formatted(p.getStartDate().getMonthValue() <= 6 ? 1 : 2, p.getStartDate().format(YEAR_LABEL));
            case YEAR -> p.getStartDate().format(YEAR_LABEL);
            case DAY -> p.getStartDate().toString();
        };
    }

    private record LockSummary(UUID lockId, LockType lockType, Instant graceWindowUntil, String reason) {}

    private LockSummary lockSummary(PeriodLock lock, String reason) {
        return new LockSummary(lock.getId(), lock.getLockType(), lock.getGraceWindowUntil(), reason);
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (role.equals(a.getAuthority())) return true;
        }
        return false;
    }
}
