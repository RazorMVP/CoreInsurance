package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.entity.BaseEntity;
import com.nubeero.cia.common.entity.LockableByPeriod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Interceptor;
import org.hibernate.type.Type;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Hibernate {@link Interceptor} that rejects writes to {@link LockableByPeriod}
 * entities whose {@link LockableByPeriod#getLockDate()} resolves to a fiscal
 * period that is HARD-closed, or SOFT-closed past the grace window without
 * the caller holding {@code FINANCE_OVERRIDE_LOCK}.
 *
 * <h2>Why an Interceptor</h2>
 * <p>Service-layer guards only fire on calls that go through the service.
 * Repository writes, JPA cascades, bulk JPQL — anything that bypasses the
 * service bypasses the guard. Hibernate's Interceptor sits at the ORM choke
 * point: every persistent write (INSERT in {@link #onSave}, UPDATE in
 * {@link #onFlushDirty}) passes through it. Defence by topology, not by
 * convention.
 *
 * <h2>Reversal carve-out</h2>
 * <p>Reversal entities (entities that exist to offset a previously-posted
 * row, identified by {@link LockableByPeriod#isReversal()}) are skipped —
 * blocking reversals would prevent corrections, which is the exact failure
 * mode that turns audit findings into management-letter findings.
 *
 * <h2>Performance</h2>
 * <p>Hot-path budget is &lt;2 % p99 overhead on a 10k-write workload. The
 * instanceof check costs ~1 ns; lookups are memoised by {@link
 * FiscalPeriodLookupCache} per request so the second-through-Nth call for
 * the same lock date is a hash-map hit.
 *
 * @since Module 12, Slice 1.7
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PeriodLockInterceptor implements Interceptor {

    @Lazy private final PeriodLockService lockService;
    @Lazy private final AuditService auditService;

    @Override
    public boolean onSave(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types) {
        check(entity);
        return false;   // we don't mutate state
    }

    @Override
    public boolean onFlushDirty(Object entity, Object id, Object[] currentState, Object[] previousState,
                                String[] propertyNames, Type[] types) {
        check(entity);
        return false;
    }

    private void check(Object entity) {
        if (!(entity instanceof LockableByPeriod lockable)) return;

        LockDecision decision = lockService.checkWrite(lockable);
        switch (decision.outcome()) {
            case ALLOW -> { /* no-op */ }
            case OVERRIDE -> recordOverride(entity, lockable, decision);
            case REJECT -> {
                log.warn("Period-lock REJECT: entity={} lockDate={} period={} status={} reason={}",
                    entity.getClass().getSimpleName(), lockable.getLockDate(),
                    decision.periodLabel(), decision.status(), decision.reason());
                throw new PeriodLockedException(decision);
            }
        }
    }

    private void recordOverride(Object entity, LockableByPeriod lockable, LockDecision decision) {
        // The override-using user is already past the grace window — preserving
        // the evidence is the whole point of granting the role in the first place.
        String entityId = entity instanceof BaseEntity be && be.getId() != null ? be.getId().toString() : "(pre-id)";
        auditService.log(
            entity.getClass().getSimpleName(), entityId, AuditAction.LOCK_OVERRIDE,
            null,
            new OverridePayload(decision.periodId(), decision.periodLabel(), lockable.getLockDate(),
                decision.graceEndsAt())
        );
        log.info("Period-lock OVERRIDE used: entity={} lockDate={} period={} graceEndedAt={}",
            entity.getClass().getSimpleName(), lockable.getLockDate(),
            decision.periodLabel(), decision.graceEndsAt());
    }

    /** Payload JSON-serialised into {@code audit_log.new_value} on override use. */
    private record OverridePayload(
        java.util.UUID periodId,
        String periodLabel,
        java.time.LocalDate lockDate,
        java.time.Instant graceEndedAt
    ) {}
}
