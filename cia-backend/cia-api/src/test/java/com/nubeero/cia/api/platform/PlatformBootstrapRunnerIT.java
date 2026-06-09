package com.nubeero.cia.api.platform;

import com.nubeero.cia.setup.keycloak.PlatformRoles;
import com.nubeero.cia.storage.DocumentStorageService;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dasniko.testcontainers.keycloak.KeycloakContainer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT — verifies that {@link PlatformBootstrapRunner} fires at Spring context startup
 * and provisions the platform realm when {@code cia.platform.bootstrap.enabled=true}.
 *
 * <p>Uses a real Keycloak 24 Testcontainer (same image + harness as
 * {@code KeycloakItSupport}) and a real Postgres 16 Testcontainer (same pattern
 * as {@code FinanceWebItSupport}) so the full Spring Boot context starts cleanly.
 *
 * <p>The runner is gated by {@code @ConditionalOnProperty(...havingValue="true")},
 * so the normal IT suite (which never sets this flag) is unaffected.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        // Gate: turn the platform bootstrap runner ON.
        "cia.platform.bootstrap.enabled=true",
        // Supply a non-blank tempPassword so bootstrap does not fail validation.
        "cia.platform.bootstrap.admin-temp-password=SuperTemp-Pass123!",
        // Disable Redis-backed bucket4j — no Redis in IT.
        "bucket4j.enabled=false",
        // Disable the NAICOM/NIID stubs that try to dial external URLs.
        "cia.naicom.mode=stub",
        "cia.niid.mode=stub"
    }
)
@Testcontainers
class PlatformBootstrapRunnerIT {

    // ── Keycloak container ──────────────────────────────────────────────────
    // Mirrors KeycloakItSupport's container declaration exactly (same image,
    // same env, same wait strategy).

    @Container
    @SuppressWarnings("resource")
    static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:24.0")
                    .withAdminUsername("admin")
                    .withAdminPassword("admin")
                    .withEnv("KEYCLOAK_ADMIN", "admin")
                    .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
                    .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                    .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                    .waitingFor(Wait.forHttp("/realms/master")
                            .forPort(8080)
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofMinutes(4)))
                    .withStartupTimeout(Duration.ofMinutes(4));

    // ── Postgres container ──────────────────────────────────────────────────
    // Intentional singleton: no @Container so Testcontainers doesn't stop it per-class and
    // invalidate the Spring-cached context that holds the bound JDBC URL (mirrors FinanceWebItSupport).

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciaplatformtest")
                    .withUsername("ciaplatformtest")
                    .withPassword("ciaplatformtest");

    static {
        POSTGRES.start();
    }

    // ── External-service mocks (prevent startup network calls) ─────────────

    /** Prevents TenantIssuerJwtAuthenticationManagerResolver from calling OIDC discovery. */
    @MockBean JwtDecoder jwtDecoder;

    /** Prevents TemporalConfig from dialling localhost:7233. */
    @MockBean WorkflowServiceStubs workflowServiceStubs;
    @MockBean WorkflowClient workflowClient;
    @MockBean WorkerFactory workerFactory;

    /**
     * Satisfies DocumentTemplateService. No "local" impl exists in the storage
     * module — all impls are conditional on minio/s3/gcs/azure.
     */
    @MockBean DocumentStorageService documentStorageService;

    // ── Dynamic properties ─────────────────────────────────────────────────

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        // Postgres
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");

        // Keycloak admin client — mirrors KeycloakItSupport.keycloakProps exactly.
        // Disabling SSL on master realm happens in the @BeforeAll (disableMasterRealmSsl
        // equivalent) but here we inline it as part of the static context startup.
        registry.add("cia.keycloak.admin.enabled",      () -> "true");
        registry.add("cia.keycloak.admin.server-url",   KEYCLOAK::getAuthServerUrl);
        registry.add("cia.keycloak.admin.admin-realm",  () -> "master");
        registry.add("cia.keycloak.admin.client-id",    () -> "admin-cli");
        registry.add("cia.keycloak.admin.username",     () -> "admin");
        registry.add("cia.keycloak.admin.password",     () -> "admin");
        registry.add("cia.keycloak.admin.target-realm", () -> "cia-test");

        // Platform realm URL (used by TenantIssuerJwtAuthenticationManagerResolver
        // as the trusted base — it won't be called at all because JwtDecoder is
        // mocked, but the property must be present).
        registry.add("cia.keycloak.server-url", KEYCLOAK::getAuthServerUrl);

        // Platform bootstrap — realm name so assertions can target it.
        registry.add("cia.platform.realm", () -> "platform-bootstrap-it");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static void disableMasterRealmSsl() {
        try {
            var result = KEYCLOAK.execInContainer(
                "/opt/keycloak/bin/kcadm.sh", "update", "realms/master",
                "-s", "sslRequired=none",
                "--no-config",
                "--server",   "http://localhost:8080",
                "--realm",    "master",
                "--user",     "admin",
                "--password", "admin"
            );
            if (result.getExitCode() != 0) {
                System.err.println("[PlatformBootstrapRunnerIT] kcadm.sh exit="
                        + result.getExitCode() + " stderr=" + result.getStderr());
            }
        } catch (Exception e) {
            System.err.println("[PlatformBootstrapRunnerIT] disableMasterRealmSsl: " + e.getMessage());
        }
    }

    private static Keycloak adminClient() {
        disableMasterRealmSsl();
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        long deadline = System.currentTimeMillis() + 90_000L;
        RuntimeException lastErr = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                admin.realms().findAll();
                return admin;
            } catch (RuntimeException e) {
                lastErr = e;
                try { Thread.sleep(500L); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for Keycloak admin", ie);
                }
            }
        }
        throw new IllegalStateException("Keycloak admin client did not become ready within 90s", lastErr);
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PlatformBootstrapRunner — platform realm is provisioned on context startup")
    void platformRealmProvisionedOnStartup() {
        // The runner fired during @SpringBootTest context startup.
        // Assert against the live Keycloak container.
        Keycloak admin = adminClient();
        String realm = "platform-bootstrap-it";

        List<String> roleNames = admin.realm(realm).roles().list()
                .stream().map(r -> r.getName()).toList();

        assertThat(roleNames)
                .as("platform realm must contain SUPER_ADMIN role")
                .contains(PlatformRoles.SUPER_ADMIN);
    }

    @Test
    @DisplayName("PlatformBootstrapRunner — superadmin user exists in the provisioned realm")
    void superAdminUserExistsAfterBootstrap() {
        Keycloak admin = adminClient();
        String realm = "platform-bootstrap-it";

        var users = admin.realm(realm).users().search("superadmin", true);
        assertThat(users)
                .as("superadmin user must be present in the platform realm")
                .hasSize(1);

        assertThat(users.get(0).getRequiredActions())
                .as("superadmin must require UPDATE_PASSWORD on first login")
                .contains("UPDATE_PASSWORD");
    }
}
