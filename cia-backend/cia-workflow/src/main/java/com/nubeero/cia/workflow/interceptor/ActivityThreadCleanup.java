package com.nubeero.cia.workflow.interceptor;

/**
 * Marker for module-local ThreadLocal-style state that needs to be cleared
 * after every Temporal activity invocation on a worker thread.
 *
 * <p>Slice 1.8a introduces this because {@code cia-finance}'s
 * {@code FiscalPeriodLookupCache} keeps a per-thread {@code HashMap} when
 * no HTTP request scope is bound (i.e. on Temporal worker threads). Worker
 * threads are pooled — without an explicit cleanup hook the cache from
 * tenant A bleeds into the next activity for tenant B and silently masks
 * lock-state changes.
 *
 * <p>{@code cia-workflow} owns the interface so it does not have to import
 * {@code cia-finance}. Any module is free to contribute additional cleanups
 * (e.g. an SLF4J MDC clear) by registering a bean implementing this
 * interface; {@link TenantAwareWorkerInterceptor} invokes them all in the
 * activity-execution {@code finally} block.
 */
@FunctionalInterface
public interface ActivityThreadCleanup {
    void clear();
}
