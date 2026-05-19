package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.entity.BaseEntity;
import com.nubeero.cia.common.entity.LockableByPeriod;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Interceptor;
import org.hibernate.type.Type;
import org.springframework.beans.factory.annotation.Autowired;
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
public class PeriodLockInterceptor implements Interceptor {

    // ─────────────────────────────────────────────────────────────────────────
    // Manual constructor (not Lombok-generated) because @Lazy only takes
    // effect on Spring's constructor-parameter dependency resolution path
    // when it's annotated ON THE PARAMETER. Lombok's @RequiredArgsConstructor
    // copies field annotations to the field declarations, not the generated
    // constructor parameters — so Spring eagerly resolves PeriodLockService
    // here, which transitively needs FiscalPeriodRepository → EntityManager →
    // EntityManagerFactory — but THIS interceptor is wired INTO the EMF
    // construction, so a cycle forms and the IT context fails to load.
    //
    // Keeping the constructor manual makes the lazy contract explicit at the
    // wiring point rather than at the unrelated field declaration. Same beans,
    // same behaviour at runtime — only the bean-initialisation order differs.
    // ─────────────────────────────────────────────────────────────────────────
    private final PeriodLockService lockService;
    private final AuditService auditService;

    @Autowired
    public PeriodLockInterceptor(@Lazy PeriodLockService lockService,
                                 @Lazy AuditService auditService) {
        this.lockService = lockService;
        this.auditService = auditService;
    }

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

    // Reentry guard. Hibernate's default AUTO flush mode flushes pending
    // writes before every JPA query — so when our check() calls into
    // PeriodLockService → cache → resolver → repository.findFirst, Hibernate
    // flushes the in-flight LockableByPeriod entity that triggered THIS
    // check() pass, firing onFlushDirty → check() recursively on the same
    // entity, on the same thread, with the same in-flight lookupCache load
    // pending. We exit fast on reentry; the outer call already owns the
    // check decision for this save.
    private static final ThreadLocal<Boolean> CHECKING = ThreadLocal.withInitial(() -> false);

    private void check(Object entity) {
        if (!(entity instanceof LockableByPeriod lockable)) return;
        if (CHECKING.get()) return;
        CHECKING.set(true);
        try {
            doCheck(entity, lockable);
        } finally {
            CHECKING.set(false);
        }
    }

    private void doCheck(Object entity, LockableByPeriod lockable) {

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
