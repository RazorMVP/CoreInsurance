package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Scope-aware singleton memoising {@code (tenantId, lockDate) → (FiscalPeriod,
 * active lock)} lookups for the {@link PeriodLockInterceptor}.
 *
 * <h2>Why scope-aware, not pure {@code @RequestScope}</h2>
 * <p>The cache hot path is invoked on every Hibernate flush. Slice 1.7 shipped
 * this as {@code @RequestScope} for tenant isolation (Spring creates one
 * instance per HTTP request, no cross-request key collision). That works under
 * HTTP traffic, but breaks the moment Slice 1.8's
 * {@code RetroactiveJournalBackfillWorkflow} runs — Temporal activities
 * execute on worker threads with no request bound, the scoped-proxy throws
 * {@code IllegalStateException: No thread-bound request found}, and the
 * backfill cannot complete its first JE.
 *
 * <p>This bean is now a plain singleton with two storage backends, picked at
 * each {@link #get} call:
 * <ol>
 *   <li><b>Request attribute</b> — when {@code RequestContextHolder} has a
 *       bound request, the cache map lives as a {@link
 *       RequestAttributes#SCOPE_REQUEST}-scoped attribute. Spring's request
 *       lifecycle clears it at request end, exactly mirroring the old
 *       {@code @RequestScope} semantics with zero code changes at call
 *       sites.</li>
 *   <li><b>ThreadLocal fallback</b> — when no request is bound (Temporal
 *       activity, scheduled job, batch import), a per-thread {@link HashMap}
 *       takes over. Callers in non-HTTP contexts must invoke
 *       {@link #clearThreadCache()} at activity boundaries to prevent
 *       unbounded growth on pooled worker threads. The Slice 1.8 Temporal
 *       {@code WorkerInterceptor} owns that lifecycle.</li>
 * </ol>
 *
 * <h2>Tenant isolation</h2>
 * <p>The cache key is {@code (tenantId, lockDate)} — not just {@code lockDate}.
 * Under the old {@code @RequestScope} design, Spring's per-request scoping
 * alone guaranteed tenant isolation. Under the new ThreadLocal-fallback path,
 * a pooled worker thread reused across tenants without an intermediate clear
 * would risk returning tenant A's snapshot to tenant B. Including tenantId in
 * the key reduces that to a cache miss instead of a correctness bug — and
 * costs nothing on the hot path. {@link TenantContext#getTenantId()} returning
 * {@code null} is mapped to a sentinel so the key is never null itself.
 *
 * <h2>Migration note</h2>
 * <p>The public {@link #get(LocalDate, Function)} signature is unchanged from
 * Slice 1.7 — only the bean scope and key shape changed. Existing call sites
 * in {@link PeriodLockInterceptor} require no edits. Integration tests that
 * previously bound a {@code MockHttpServletRequest} via
 * {@code RequestContextHolder} continue to exercise the request-attribute
 * path; tests that don't bind one now silently exercise the ThreadLocal path.
 *
 * @since Module 12, Slice 1.7 (request-scoped); refactored Slice 1.7-fix
 *        (scope-aware singleton)
 */
@Component
public class FiscalPeriodLookupCache {

    private static final String REQUEST_ATTR_KEY = "cia.finance.FiscalPeriodLookupCache.cache";
    private static final String UNBOUND_TENANT = "<unbound>";

    /**
     * Per-thread fallback used when no HTTP request is bound. Cleared via
     * {@link #clearThreadCache()} at non-HTTP scope boundaries (Temporal
     * activity end, scheduled-job iteration end). Unbounded growth on a
     * single thread would otherwise be the failure mode on long-lived
     * worker threads.
     */
    private final ThreadLocal<Map<CacheKey, Optional<PeriodSnapshot>>> threadCache =
        ThreadLocal.withInitial(HashMap::new);

    /**
     * Compute-if-absent: invokes the loader exactly once per
     * {@code (tenantId, lockDate)} per scope. The result {@code Optional} is
     * cached (including the empty case) so a lock date that doesn't resolve
     * to a period also avoids repeat queries.
     */
    public Optional<PeriodSnapshot> get(LocalDate lockDate, Function<LocalDate, Optional<PeriodSnapshot>> loader) {
        CacheKey key = new CacheKey(currentTenant(), lockDate);
        return currentMap().computeIfAbsent(key, k -> loader.apply(k.lockDate()));
    }

    /**
     * Drops the ThreadLocal-fallback map for the current thread. Callers in
     * non-HTTP contexts (Temporal activity boundary, scheduled-job iteration,
     * batch worker) MUST invoke this at the end of each unit of work — the
     * cache would otherwise accumulate entries across unrelated tenants /
     * lock dates and leak through pooled threads.
     *
     * <p>Has no effect when an HTTP request is bound; the request-attribute
     * path is cleaned up automatically by Spring's request lifecycle.
     */
    public void clearThreadCache() {
        threadCache.remove();
    }

    @SuppressWarnings("unchecked")
    private Map<CacheKey, Optional<PeriodSnapshot>> currentMap() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra != null) {
            Map<CacheKey, Optional<PeriodSnapshot>> map =
                (Map<CacheKey, Optional<PeriodSnapshot>>) ra.getAttribute(REQUEST_ATTR_KEY, RequestAttributes.SCOPE_REQUEST);
            if (map == null) {
                map = new HashMap<>();
                ra.setAttribute(REQUEST_ATTR_KEY, map, RequestAttributes.SCOPE_REQUEST);
            }
            return map;
        }
        return threadCache.get();
    }

    private String currentTenant() {
        String t = TenantContext.getTenantId();
        return t != null ? t : UNBOUND_TENANT;
    }

    private record CacheKey(String tenantId, LocalDate lockDate) {}

    /**
     * Immutable snapshot of the period + its active lock at the moment the
     * cache was loaded. Stored as a record so equality semantics are sane
     * and the interceptor never holds a managed Hibernate entity reference
     * across flush boundaries (which would be a use-after-detach landmine).
     */
    public record PeriodSnapshot(
        UUID periodId,
        String periodLabel,
        FiscalPeriodStatus status,
        LocalDate startDate,
        LocalDate endDate,
        Optional<ActiveLock> activeLock
    ) {}

    public record ActiveLock(
        LockType lockType,
        Instant lockedAt,
        Instant graceWindowUntil
    ) {}
}
