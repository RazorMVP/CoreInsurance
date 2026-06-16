package com.nubeero.cia.api.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * IT for {@link PlatformAuditService} paged/filtered reads against a real PostgreSQL container.
 *
 * <p>No Spring context — the service takes a {@link JdbcTemplate} directly. The Postgres container
 * is inlined here (mirroring {@code PlatformOnboardingE2EIT}) because the {@code tenant}-package
 * {@code TenantProvisioningItSupport} base is package-private. The {@code public.platform_audit_log}
 * table is created in {@code @BeforeEach} (idempotent, mirrors V67 DDL) with {@code target_schema}
 * relaxed to NULL (V71's public-run effect) because this IT does not run the Flyway sweep itself.
 * Every test starts from a fully-cleaned table so the per-schema and count assertions are
 * deterministic on the shared container.
 */
class PlatformAuditServiceIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciaaudit")
                    .withUsername("ciaaudit")
                    .withPassword("ciaaudit");

    static final HikariDataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(4);
        DATA_SOURCE = new HikariDataSource(cfg);
    }

    private JdbcTemplate jdbc;
    private PlatformAuditService audit;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(DATA_SOURCE);

        jdbc.execute("CREATE TABLE IF NOT EXISTS public.platform_audit_log ("
            + " id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),"
            + " action         VARCHAR(32)  NOT NULL,"
            + " target_schema  VARCHAR(63)  NOT NULL,"
            + " actor_username VARCHAR(255) NOT NULL,"
            + " actor_realm    VARCHAR(63)  NOT NULL,"
            + " detail         JSONB,"
            + " source_ip      VARCHAR(64),"
            + " at             TIMESTAMPTZ  NOT NULL DEFAULT now()"
            + ")");
        // V71 (public run) relaxes the NOT NULL so super-admin (user-targeted) rows insert.
        // This IT doesn't run V71 itself, so apply the relax idempotently here.
        jdbc.execute("ALTER TABLE public.platform_audit_log ALTER COLUMN target_schema DROP NOT NULL");

        // Fully clean between tests so the per-schema + count assertions are deterministic.
        jdbc.update("DELETE FROM public.platform_audit_log");

        audit = new PlatformAuditService(jdbc);
    }

    @Test
    @DisplayName("recentForSchema — returns only rows for the given target schema, newest first")
    void recentForSchema_filtersBySchema() {
        audit.record("ONBOARD",  "tenant_a", "sa", "platform", null, "1.1.1.1");
        audit.record("SUSPEND",  "tenant_b", "sa", "platform", null, "1.1.1.1");
        audit.record("ACTIVATE", "tenant_a", "sa", "platform", null, "1.1.1.1");

        var rows = audit.recentForSchema("tenant_a", 10);

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.targetSchema()).isEqualTo("tenant_a"));
        assertThat(rows.get(0).action()).isEqualTo("ACTIVATE"); // newest first
    }

    @Test
    @DisplayName("recent(page,size,filter) — paginates and honours the optional target-schema filter")
    void recent_paged_andFiltered() {
        for (int i = 0; i < 5; i++) audit.record("ONBOARD", "tenant_p", "sa", "platform", null, "1.1.1.1");
        audit.record("ONBOARD", "tenant_q", "sa", "platform", null, "1.1.1.1");

        assertThat(audit.recent(0, 2, null)).hasSize(2);            // first page, unfiltered
        assertThat(audit.recent(0, 50, "tenant_p")).hasSize(5);     // filtered
        assertThat(audit.count(null)).isEqualTo(6L); // @BeforeEach DELETEs all rows, this test inserts exactly 6
        assertThat(audit.count("tenant_p")).isEqualTo(5L);
    }

    @Test
    @DisplayName("recent — super-admin rows have NULL target_schema and are excluded by recentForSchema")
    void recent_nullSchemaRows_excludedFromPerSchema() {
        audit.record("INVITE_SUPER_ADMIN", null, "sa", "platform", "{\"username\":\"x\"}", "1.1.1.1");

        assertThat(audit.recent(0, 50, null))
                .anySatisfy(r -> assertThat(r.action()).isEqualTo("INVITE_SUPER_ADMIN"));
        assertThat(audit.recentForSchema("anything", 50))
                .noneSatisfy(r -> assertThat(r.action()).isEqualTo("INVITE_SUPER_ADMIN"));
    }
}
