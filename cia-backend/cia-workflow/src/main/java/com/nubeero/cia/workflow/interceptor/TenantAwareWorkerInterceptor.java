package com.nubeero.cia.workflow.interceptor;

import com.nubeero.cia.common.tenant.TenantContext;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Worker-level interceptor that guarantees per-activity thread-local
 * hygiene on the Temporal worker thread pool.
 *
 * <p>Slice 1.8a (Module 12 — Period-End Closures). Temporal hands the same
 * worker threads to many activities across many tenants. Without an
 * explicit cleanup hook, two failure modes appear:
 * <ol>
 *   <li>{@link TenantContext} from one activity bleeds into the next, so
 *       the next activity's Hibernate {@code MultiTenantConnectionProvider}
 *       routes to the wrong schema — a hard correctness bug.</li>
 *   <li>{@code FiscalPeriodLookupCache}'s ThreadLocal fallback (the
 *       no-HTTP-request-scope branch added in Slice 1.7-fix) keeps cache
 *       entries from tenant A on a worker thread that subsequently runs
 *       work for tenant B, masking lock-state changes — a silent
 *       correctness bug.</li>
 * </ol>
 *
 * <p>The interceptor does not <em>set</em> the tenant — each activity impl
 * reads {@code tenantId} from its request record and calls
 * {@code TenantContext.setTenantId} explicitly so the binding is visible in
 * the impl source. The interceptor exists to enforce the matching
 * teardown, and to run any extra {@link ActivityThreadCleanup} hooks (e.g.
 * the finance lookup cache) in a {@code finally} block so a thrown activity
 * cannot leak state.
 *
 * <p>Workflow execution is not intercepted — Temporal workflows are
 * deterministic and must not touch tenant-specific I/O state.
 */
@Slf4j
@RequiredArgsConstructor
public class TenantAwareWorkerInterceptor extends WorkerInterceptorBase {

    private final List<ActivityThreadCleanup> cleanups;

    @Override
    public ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next) {
        return new ActivityInboundCallsInterceptorBase(next) {
            @Override
            public ActivityOutput execute(ActivityInput input) {
                try {
                    return super.execute(input);
                } finally {
                    TenantContext.clear();
                    for (ActivityThreadCleanup cleanup : cleanups) {
                        try {
                            cleanup.clear();
                        } catch (RuntimeException ex) {
                            // Never let a cleanup hook mask the activity result.
                            log.warn("ActivityThreadCleanup {} threw on clear()",
                                    cleanup.getClass().getSimpleName(), ex);
                        }
                    }
                }
            }
        };
    }
}
