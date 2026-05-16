package com.nubeero.cia.finance.backfill;

import com.nubeero.cia.finance.gl.FiscalPeriodLookupCache;
import com.nubeero.cia.workflow.interceptor.ActivityThreadCleanup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Registers a per-activity cleanup hook with the
 * {@code TenantAwareWorkerInterceptor} (Slice 1.8a) so the finance
 * {@code FiscalPeriodLookupCache} ThreadLocal fallback (Slice 1.7-fix) is
 * cleared between activity invocations on the pooled worker thread.
 *
 * <p>Without this hook the cache from tenant A's activity bleeds into the
 * next activity for tenant B and silently masks lock-state changes — a
 * correctness bug rather than a perf bug.
 *
 * <p>This is a thin adapter so {@code cia-workflow} never needs to import
 * {@code cia-finance}; the dependency arrow points the other way only.
 */
@Component
@RequiredArgsConstructor
class FinanceActivityCleanup implements ActivityThreadCleanup {

    private final FiscalPeriodLookupCache lookupCache;

    @Override
    public void clear() {
        lookupCache.clearThreadCache();
    }
}
