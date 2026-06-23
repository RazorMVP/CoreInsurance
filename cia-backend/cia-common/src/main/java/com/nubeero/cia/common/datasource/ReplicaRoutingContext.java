package com.nubeero.cia.common.datasource;

import java.util.function.Supplier;

/**
 * Thread-bound opt-in flag that requests the current work be routed to a read
 * replica (when one is configured).
 *
 * <p><b>Why a deliberate opt-in and not the {@code @Transactional(readOnly=true)}
 * flag.</b> There are ~167 {@code readOnly=true} read methods across the app —
 * setup CRUD, finance/GL reads, customer/policy/claim lookups. Routing all of
 * them to a replica would expose every read-after-write flow to replication lag
 * (post a receipt on the primary, immediately read the balance off a lagging
 * replica → stale). For regulated financial data that is unacceptable. So replica
 * routing is opt-in <em>per read path</em> — only the report-heavy, lag-tolerant
 * {@code cia-reports} analytical queries enter this context.
 *
 * <p>The flag is read by {@link ReplicaRoutingDataSource#determineCurrentLookupKey()}
 * when a connection is borrowed. It is a no-op unless a replica datasource is
 * actually wired (i.e. {@code cia.datasource.replica.url} is set) — without one,
 * the application datasource is a plain pool and nobody reads this flag, so
 * {@link #onReplica} is harmless to call unconditionally.
 *
 * <p>Lives in {@code cia-common} (no Spring dependency) so the {@code cia-reports}
 * read path can enter it while the routing datasource (which reads it) is wired
 * in {@code cia-api}.
 */
public final class ReplicaRoutingContext {

    private static final ThreadLocal<Boolean> PREFER_REPLICA = new ThreadLocal<>();

    private ReplicaRoutingContext() {
    }

    /** @return true if the current thread has requested replica routing. */
    public static boolean isReplicaPreferred() {
        return Boolean.TRUE.equals(PREFER_REPLICA.get());
    }

    /**
     * Runs {@code action} with replica routing requested for the current thread,
     * restoring the prior state on exit. Reentrant-safe: a nested call will not
     * clear the flag set by an outer one.
     *
     * <p>Call this <em>before</em> the first JDBC statement of the unit of work —
     * Hibernate acquires the connection lazily on first use and holds it for the
     * transaction, so the flag must be set before that first borrow for the whole
     * transaction to land on the replica.
     */
    public static <T> T onReplica(Supplier<T> action) {
        boolean alreadySet = isReplicaPreferred();
        if (!alreadySet) {
            PREFER_REPLICA.set(Boolean.TRUE);
        }
        try {
            return action.get();
        } finally {
            if (!alreadySet) {
                PREFER_REPLICA.remove();
            }
        }
    }
}
