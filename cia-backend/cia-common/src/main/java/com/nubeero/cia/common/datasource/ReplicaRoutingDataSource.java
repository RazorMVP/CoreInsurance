package com.nubeero.cia.common.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Routes each borrowed connection to the {@code REPLICA} pool when the current
 * thread has opted in via {@link ReplicaRoutingContext}, otherwise to the
 * {@code PRIMARY} pool.
 *
 * <p>This sits underneath the multi-tenant layer:
 * {@code MultiTenantConnectionProvider.getConnection(tenant)} borrows a connection
 * from <em>this</em> datasource and then issues {@code SET search_path} on it — so
 * primary/replica selection and per-tenant schema isolation compose, and the
 * {@code search_path} logic is identical on either pool.
 *
 * <p>The default target is the primary, so any borrow with no replica preference
 * (every write, Flyway migrations at startup, and all non-report reads) goes to
 * the primary. Wired only by {@code ReadReplicaDataSourceConfig} when a replica
 * URL is configured; otherwise the application runs on a plain single pool and
 * this class is never instantiated.
 */
public class ReplicaRoutingDataSource extends AbstractRoutingDataSource {

    public static final String PRIMARY = "PRIMARY";
    public static final String REPLICA = "REPLICA";

    @Override
    protected Object determineCurrentLookupKey() {
        return ReplicaRoutingContext.isReplicaPreferred() ? REPLICA : PRIMARY;
    }
}
