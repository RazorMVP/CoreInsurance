package com.nubeero.cia.api.config;

import com.nubeero.cia.common.datasource.ReplicaRoutingContext;
import com.nubeero.cia.common.datasource.ReplicaRoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decisive proof of the read-replica routing primitive against TWO real Postgres
 * containers (a "primary" and a "replica"), each seeded with a distinguishable
 * marker. Borrows go through the real {@link ReplicaRoutingDataSource} and set
 * {@code search_path} exactly as {@code MultiTenantConnectionProvider} does, so a
 * single test proves three things at once:
 *
 * <ol>
 *   <li>a default borrow (no replica context) lands on the <b>primary</b>;</li>
 *   <li>a borrow inside {@link ReplicaRoutingContext#onReplica} lands on the
 *       <b>replica</b>;</li>
 *   <li>{@code SET search_path TO "&lt;tenant&gt;", public} works on the replica
 *       connection — tenant isolation is intact on either pool.</li>
 * </ol>
 *
 * <p>(The two containers are independent databases — there is no streaming
 * replication between them. Seeding different marker values is what makes the
 * routed pool observable; it is not modelling real replication.)
 *
 * @since db-backup-dr Deliverable B (read-replica routing)
 */
@Testcontainers
class ReplicaRoutingDataSourceIT {

    @Container
    static final PostgreSQLContainer<?> PRIMARY = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final PostgreSQLContainer<?> REPLICA = new PostgreSQLContainer<>("postgres:16-alpine");

    private static HikariDataSource primaryPool;
    private static HikariDataSource replicaPool;
    private static DataSource routing;

    @BeforeAll
    static void wireRouting() throws Exception {
        primaryPool = buildPool(PRIMARY);
        replicaPool = buildPool(REPLICA);
        // Marker in a tenant schema (not public) so the read also proves search_path.
        seedMarker(primaryPool, "primary");
        seedMarker(replicaPool, "replica");

        ReplicaRoutingDataSource rds = new ReplicaRoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(ReplicaRoutingDataSource.PRIMARY, primaryPool);
        targets.put(ReplicaRoutingDataSource.REPLICA, replicaPool);
        rds.setTargetDataSources(targets);
        rds.setDefaultTargetDataSource(primaryPool);
        rds.afterPropertiesSet();
        routing = rds;
    }

    @AfterAll
    static void closePools() {
        if (primaryPool != null) primaryPool.close();
        if (replicaPool != null) replicaPool.close();
    }

    @Test
    void defaultBorrowRoutesToPrimary() throws Exception {
        assertThat(readMarkerViaSearchPath()).isEqualTo("primary");
    }

    @Test
    void onReplicaBorrowRoutesToReplica_andSearchPathWorks() {
        String marker = ReplicaRoutingContext.onReplica(() -> {
            try {
                return readMarkerViaSearchPath();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(marker).isEqualTo("replica");
    }

    @Test
    void contextIsClearedAfterOnReplica_soNextBorrowIsPrimaryAgain() throws Exception {
        ReplicaRoutingContext.onReplica(() -> null);
        assertThat(readMarkerViaSearchPath()).isEqualTo("primary");
    }

    /**
     * Mirrors {@code MultiTenantConnectionProvider.getConnection}: borrow via the
     * routing datasource, pin the tenant search_path, then read the marker — so the
     * assertion covers both which pool was chosen and that search_path resolves on it.
     */
    private String readMarkerViaSearchPath() throws Exception {
        try (Connection c = routing.getConnection(); Statement st = c.createStatement()) {
            st.execute("SET search_path TO \"tenant_probe\", public");
            try (ResultSet rs = st.executeQuery("SELECT node FROM routing_probe")) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static HikariDataSource buildPool(PostgreSQLContainer<?> container) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(container.getJdbcUrl())
                .username(container.getUsername())
                .password(container.getPassword())
                .build();
    }

    private static void seedMarker(DataSource ds, String node) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS tenant_probe");
            st.execute("CREATE TABLE tenant_probe.routing_probe (node text)");
            st.execute("INSERT INTO tenant_probe.routing_probe (node) VALUES ('" + node + "')");
        }
    }
}
