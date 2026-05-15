package com.nubeero.cia.finance.gl;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Request-scoped memoisation of {@code (lockDate) → (FiscalPeriod, active lock)}
 * lookups for the {@link PeriodLockInterceptor}.
 *
 * <h2>Why request-scoped, not application-scoped</h2>
 * <p>The interceptor is invoked on every flush. A naïve per-flush lookup
 * against {@code fiscal_period} + {@code period_lock} would dominate the
 * &lt;2 % p99 budget under bulk-write workloads (e.g. SubledgerPostingService
 * processing 200 ClaimSettled events in one HTTP request). Caching at request
 * scope removes the duplicate I/O without introducing cross-request
 * invalidation complexity — when the request ends, the cache dies.
 *
 * <h2>Why not application-scoped with manual invalidation</h2>
 * <p>An app-wide cache would need explicit invalidation on
 * {@code softClose / hardClose / reopen}. That's both correct and reasonable
 * but introduces a multi-tenant invalidation surface (per-tenant keying,
 * publish events on close, distributed cache for HA pods, etc.). Slice 1.7's
 * mandate is &lt;2 % p99; the request scope clears that bar without the
 * coordination cost. If JMH later shows we need cross-request hits, the
 * cache contract is small enough to extend.
 *
 * <h2>Multi-tenancy</h2>
 * <p>Spring's request scope is per-request, and {@code TenantContext} is set
 * per request — so there's no cross-tenant key collision risk.
 *
 * @since Module 12, Slice 1.7
 */
@Component
@RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
@RequiredArgsConstructor
public class FiscalPeriodLookupCache {

    private final ConcurrentMap<LocalDate, Optional<PeriodSnapshot>> cache = new ConcurrentHashMap<>();

    /**
     * Compute-if-absent: invokes the loader exactly once per lock date per
     * request. The result {@code Optional} is cached (including the empty
     * case) so a lock date that doesn't resolve to a period also avoids
     * repeat queries.
     */
    public Optional<PeriodSnapshot> get(LocalDate lockDate, java.util.function.Function<LocalDate, Optional<PeriodSnapshot>> loader) {
        return cache.computeIfAbsent(lockDate, loader);
    }

    /**
     * Immutable snapshot of the period + its active lock at the moment the
     * cache was loaded. Stored as a record so equality semantics are sane
     * and the interceptor never holds a managed Hibernate entity reference
     * across flush boundaries (which would be a use-after-detach landmine).
     */
    public record PeriodSnapshot(
        java.util.UUID periodId,
        String periodLabel,
        FiscalPeriodStatus status,
        java.time.LocalDate startDate,
        java.time.LocalDate endDate,
        java.util.Optional<ActiveLock> activeLock
    ) {}

    public record ActiveLock(
        LockType lockType,
        java.time.Instant lockedAt,
        java.time.Instant graceWindowUntil
    ) {}
}
