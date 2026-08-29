package com.nubeero.cia.portal.grant;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository IT for {@link PartnerPortalGrantRepository} against a real PostgreSQL container
 * (Docker via Testcontainers).
 *
 * <p>Unlike a Hibernate {@code ddl-auto=create-drop} harness (see {@code RiFacInwardReferenceIT}
 * in cia-reinsurance for that pattern), this test loads the <em>real</em> V80 migration SQL — a
 * byte-identical copy at {@code src/test/resources/schema/partner_portal_grant.sql} — directly
 * via plain JDBC in {@link #createSchema()}, before the Spring context starts
 * ({@code spring.jpa.hibernate.ddl-auto=none}). This module cannot depend on {@code cia-api},
 * which owns Flyway and the real migration file (the dependency direction runs the other way),
 * so the copy is deliberate; keep both files in sync.
 *
 * <p>Running against the real DDL (rather than a Hibernate-inferred schema) matters here because
 * the partial unique index ({@code ux_ppg_user_app ... WHERE deleted_at IS NULL}) has no
 * JPA/Hibernate annotation equivalent — a Hibernate-generated schema would silently omit it, and
 * {@link #uniquePartial_blocksDuplicateActiveGrant()} would either not compile a meaningful
 * assertion or pass for the wrong reason.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PartnerPortalGrantRepositoryIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciatest")
                    .withUsername("ciatest")
                    .withPassword("ciatest");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @BeforeAll
    static void createSchema() throws Exception {
        String sql;
        try (InputStream in = PartnerPortalGrantRepositoryIT.class.getResourceAsStream(
                "/schema/partner_portal_grant.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    @Autowired
    PartnerPortalGrantRepository repo;

    private static PartnerPortalGrant grant(
            UUID user, String tenantSchema, UUID appId, GrantRole role, Instant deletedAt) {
        PartnerPortalGrant g = new PartnerPortalGrant();
        g.setPartnerUserId(user);
        g.setPartnerUserEmail("dev@insurtech.example");
        g.setTenantSchema(tenantSchema);
        g.setPartnerAppId(appId);
        g.setRole(role);
        g.setDeletedAt(deletedAt);
        return g;
    }

    @Test
    void findByUser_returnsActiveGrantsOnly() {
        UUID user = UUID.randomUUID();
        UUID appA = UUID.randomUUID();
        UUID appB = UUID.randomUUID();
        repo.save(grant(user, "tenant_acme", appA, GrantRole.MANAGER, null));
        repo.save(grant(user, "tenant_acme", appB, GrantRole.VIEWER, Instant.now())); // soft-deleted

        assertThat(repo.findByPartnerUserIdAndDeletedAtIsNull(user))
                .extracting(PartnerPortalGrant::getPartnerAppId)
                .containsExactly(appA);
    }

    @Test
    void uniquePartial_blocksDuplicateActiveGrant() {
        UUID user = UUID.randomUUID();
        UUID app = UUID.randomUUID();
        repo.saveAndFlush(grant(user, "tenant_acme", app, GrantRole.MANAGER, null));

        assertThatThrownBy(() -> repo.saveAndFlush(grant(user, "tenant_acme", app, GrantRole.VIEWER, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByPartnerUserIdAndAppId_andFindByAppId_resolveGrants() {
        UUID user = UUID.randomUUID();
        UUID app = UUID.randomUUID();
        PartnerPortalGrant saved = repo.save(grant(user, "tenant_leadway", app, GrantRole.MANAGER, null));

        assertThat(repo.findByPartnerUserIdAndPartnerAppIdAndDeletedAtIsNull(user, app))
                .map(PartnerPortalGrant::getId)
                .contains(saved.getId());
        assertThat(repo.findByPartnerAppIdAndDeletedAtIsNull(app))
                .extracting(PartnerPortalGrant::getPartnerUserId)
                .containsExactly(user);
    }
}
