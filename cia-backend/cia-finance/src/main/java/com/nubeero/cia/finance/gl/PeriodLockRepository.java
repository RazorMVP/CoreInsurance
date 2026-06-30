package com.nubeero.cia.finance.gl;

import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository over the V31 {@code period_lock} table.
 *
 * <p>The "active lock" semantics ({@code released_at IS NULL AND deleted_at
 * IS NULL}) are computed in the query name so callers cannot accidentally
 * return a released row and treat it as active. {@link
 * #findActiveByFiscalPeriodId} is the hot-path query used by every
 * interceptor invocation; index {@code idx_period_lock_active} (V31)
 * supports it.
 *
 * @since Module 12, Slice 1.7
 */
public interface PeriodLockRepository extends JpaRepository<PeriodLock, UUID> {

    /**
     * The currently active (not-released, not-deleted) lock for a period, if
     * any. Returns at most one row — a period has exactly zero or one active
     * lock at a time; multiple lock rows accumulate only across the lock
     * lifecycle (soft → release → hard, etc.).
     *
     * <p><b>flushMode=COMMIT (required):</b> this query is run by
     * {@code PeriodLockInterceptor} from <em>inside</em> a Hibernate flush
     * (onSave/onFlushDirty). Under the default AUTO flush mode it would
     * auto-flush the in-flight {@code LockableByPeriod} entity before reading,
     * and that re-entrant flush spuriously trips "Found shared references to a
     * collection" on any such entity that owns an uninitialised
     * {@code @OneToMany(orphanRemoval=true)} collection (e.g. Endorsement.risks),
     * rolling back the whole write. The lock/period rows are reference data the
     * in-flight write never touches, so skipping the pre-read flush is safe.
     * See cia-log 2026-06-30.
     */
    @QueryHints(@QueryHint(name = "org.hibernate.flushMode", value = "COMMIT"))
    Optional<PeriodLock> findFirstByFiscalPeriodIdAndReleasedAtIsNullAndDeletedAtIsNull(UUID fiscalPeriodId);

    /**
     * Full lock history for a period, newest first. Used by
     * {@code GET /api/v1/finance/period-locks/{periodId}/history} — NAICOM
     * audit evidence shows every soft/hard/release event chronologically.
     */
    List<PeriodLock> findByFiscalPeriodIdAndDeletedAtIsNullOrderByLockedAtDesc(UUID fiscalPeriodId);
}
