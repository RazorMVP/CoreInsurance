package com.nubeero.cia.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nubeero.cia.api.keycloak.KeycloakItSupport;
import com.nubeero.cia.api.platform.dto.InviteSuperAdminRequest;
import com.nubeero.cia.auth.PlatformRealmProperties;
import com.nubeero.cia.setup.keycloak.FirstAdminSpec;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * E2E super-admin lifecycle: a real {@link PlatformSuperAdminService} over a real
 * {@link KeycloakTenantProvisioner} against a Testcontainers Keycloak platform realm.
 * Mirrors {@code PlatformOnboardingE2EIT} (inline Postgres, hand-wired units, no Spring).
 *
 * <p>Exercises the full invite -> list -> revoke path plus the two service guards:
 * {@link SuperAdminExceptions.CannotRevokeSelf} (self-lockout) is asserted directly; the
 * last-super-admin guard cannot fire here because two super-admins exist throughout the test.
 *
 * <p>The {@link ObjectProvider} is a Mockito mock rather than the anonymous-class form used in
 * {@code KeycloakItSupport} — this Spring version's {@code ObjectProvider} declares
 * {@code stream()} / {@code orderedStream()} as abstract, so a 4-method anonymous class does not
 * compile. The service only ever calls {@code getIfAvailable()}, which the mock stubs.
 */
class PlatformSuperAdminE2EIT extends KeycloakItSupport {

    private static final String REALM = "platform_sa_e2e";

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciasae2e").withUsername("ciasae2e").withPassword("ciasae2e");
    static final HikariDataSource DS;
    static {
        POSTGRES.start();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(4);
        DS = new HikariDataSource(cfg);
    }

    private static Keycloak ADMIN;
    private static KeycloakTenantProvisioner provisioner;

    @BeforeAll
    static void connect() {
        ADMIN = adminClient();
        provisioner = newProvisioner(ADMIN);
        provisioner.provisionPlatformRealm(
                REALM, "cia-platform", List.of("http://localhost:5175/*"),
                new FirstAdminSpec("rootadmin", "root@platform.test", "Root", "Admin",
                        "Aa1!rootpass", UUID.randomUUID()));
    }

    private PlatformSuperAdminService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(DS);
        // public.platform_audit_log with a NULLABLE target_schema (mirrors post-V71 — super-admin
        // actions are user-targeted, not schema-targeted, so they write a NULL target_schema).
        jdbc.execute("CREATE TABLE IF NOT EXISTS public.platform_audit_log ("
            + " id UUID PRIMARY KEY DEFAULT gen_random_uuid(),"
            + " action VARCHAR(32) NOT NULL, target_schema VARCHAR(63),"
            + " actor_username VARCHAR(255) NOT NULL, actor_realm VARCHAR(63) NOT NULL,"
            + " detail JSONB, source_ip VARCHAR(64), at TIMESTAMPTZ NOT NULL DEFAULT now())");

        @SuppressWarnings("unchecked")
        ObjectProvider<Keycloak> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(ADMIN);

        var props = new PlatformRealmProperties();
        props.setRealm(REALM);
        service = new PlatformSuperAdminService(provider, Optional.of(provisioner),
                new PlatformAuditService(jdbc), props);
    }

    @Test
    void invite_list_revoke_andGuards() {
        var resp = service.invite(new InviteSuperAdminRequest("sa_e2e", "sa@e2e.test"),
                "rootadmin", REALM, "1.1.1.1");
        assertThat(resp.temporaryPassword()).startsWith("Aa1!");

        assertThat(service.list()).extracting("username").contains("rootadmin", "sa_e2e");

        // last-admin guard can't fire (2 exist); self guard blocks rootadmin revoking itself.
        assertThatThrownBy(() -> service.revoke("rootadmin", "rootadmin", REALM, "1.1.1.1"))
                .isInstanceOf(SuperAdminExceptions.CannotRevokeSelf.class);

        service.revoke("sa_e2e", "rootadmin", REALM, "1.1.1.1");
        assertThat(service.list()).extracting("username").doesNotContain("sa_e2e");

        // Both mutations landed a user-targeted (NULL target_schema) audit row in the real DB.
        assertThat(new PlatformAuditService(jdbc).recent(0, 50, null))
                .extracting("action")
                .contains("INVITE_SUPER_ADMIN", "REVOKE_SUPER_ADMIN");
    }

    /**
     * Delete the invited {@code sa_e2e} user after each test. {@code revoke} only strips the
     * SUPER_ADMIN role (not the account), so without this the realm-level user would persist and a
     * future second {@code @Test} (or a reused fork) would hit {@code AlreadyExists} on re-invite.
     * Mirrors {@code PlatformOnboardingE2EIT}'s clean-slate discipline.
     */
    @AfterEach
    void cleanup() {
        var matches = ADMIN.realm(REALM).users().search("sa_e2e", true);
        if (!matches.isEmpty()) {
            ADMIN.realm(REALM).users().get(matches.get(0).getId()).remove();
        }
    }
}
