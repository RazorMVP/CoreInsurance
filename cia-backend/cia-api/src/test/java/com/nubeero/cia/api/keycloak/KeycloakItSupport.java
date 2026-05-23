package com.nubeero.cia.api.keycloak;

import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import com.nubeero.cia.setup.user.KeycloakAdminProperties;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.keycloak.admin.client.Keycloak;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

/**
 * Shared Testcontainers Keycloak fixture for the Keycloak-touching ITs:
 * {@code KeycloakPasswordPolicySyncerIT}, {@code KeycloakRealmRoleSyncerIT},
 * {@code AccessGroupFanoutIT}, and {@code UserControllerKeycloakIT}.
 *
 * <p>One container is started per JVM (the {@code static} field is shared by
 * every subclass). With Maven's failsafe defaults the four ITs run in a
 * single forked JVM so the Keycloak cold-start amortises across the whole
 * Keycloak-IT bundle.
 *
 * <p>Backlog history. Session 112's first F1e-IT attempt used the same
 * container library and failed because the default 120-second startup wait
 * was too short on cold caches. Session 114 also confirmed via direct fix
 * that the earlier "testcontainers-keycloak transitive deps polluted the
 * test JVM" hypothesis was wrong (the real cause was Keycloak admin-client
 * type references in {@code UserService}.class; resolved by the F1e-sync
 * encapsulation strategy). This base class addresses the only remaining
 * blocker by bumping {@code withStartupTimeout} to 4 minutes and skipping
 * the realm-import-at-boot path (which made the original IT block on
 * Keycloak importing a JSON realm before serving HTTP); the test realm is
 * created at runtime via the admin API in the static initialiser instead.
 *
 * <p>Subclasses inherit the {@link Testcontainers @Testcontainers} annotation
 * which JUnit Jupiter's extension processes on the static container field.
 */
@Testcontainers
public abstract class KeycloakItSupport {

    /** Realm name created at runtime in the static initialiser. */
    public static final String TEST_REALM = "cia-test";

    @Container
    @SuppressWarnings("resource")
    static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:24.0")
                    .withAdminUsername("admin")
                    .withAdminPassword("admin")
                    // testcontainers-keycloak 3.5.1's withAdminUsername /
                    // withAdminPassword don't actually bootstrap the master
                    // realm admin on Keycloak 24 (verified by logConsumer:
                    // Keycloak emits user_not_found on the very first token
                    // request). Pass the env vars explicitly. Both KEYCLOAK_*
                    // (24.x) and KC_BOOTSTRAP_ADMIN_* (26.x) are set so this
                    // harness survives a future image bump.
                    .withEnv("KEYCLOAK_ADMIN", "admin")
                    .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
                    .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                    .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                    // testcontainers-keycloak 3.5.1 defaults to polling
                    // {@code /health/started}, but Keycloak 24's
                    // {@code start-dev} doesn't expose that endpoint without
                    // {@code --health-enabled=true}. Override with the
                    // unconditional {@code /realms/master} (always 200 once
                    // Keycloak's HTTP listener is up) so the harness doesn't
                    // wait the full 4 minutes for a probe that will never
                    // succeed against the default {@code start-dev}.
                    // testcontainers-keycloak 3.5.1's default HttpWaitStrategy
                    // probes /health/started, which Keycloak 24's start-dev
                    // doesn't expose by default. The fallback Wait.forHttp
                    // without an explicit port hits the FIRST exposed port
                    // (which on the KeycloakContainer is 8443/HTTPS, not
                    // 8080) and times out with "Connection reset". And
                    // Wait.forListeningPort() blocks waiting for ALL three
                    // exposed ports (8080, 8443, 9000) but Keycloak only
                    // binds 8080 in dev. The correct strategy is to wait
                    // explicitly on the HTTP port. The admin-token readiness
                    // check (master realm bootstrap finishing) is handled
                    // separately via the retry loop in
                    // {@link #ensureTestRealm()}.
                    .waitingFor(Wait.forHttp("/realms/master")
                            .forPort(8080)
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofMinutes(4)))
                    .withStartupTimeout(Duration.ofMinutes(4));

    /**
     * Ensure the test realm exists. Called by every subclass's
     * {@code @BeforeAll}/{@code @BeforeEach}-style hook. Creating the realm
     * via the admin API instead of via {@code --import-realm} at container
     * start avoids blocking Keycloak's HTTP listener on a slow JSON import.
     *
     * <p>Idempotent and cheap to call — re-checks existence via the admin
     * API on every call. Earlier versions of this method cached the
     * "already ensured" state in a {@code static boolean}, but that cache
     * mis-reported across test classes when running the Keycloak ITs
     * together (the cache flag was set by class A's {@code @BeforeAll}
     * before class B's {@code @BeforeAll} ran, but class B's test data
     * setup depended on the realm being present — and the cache mis-led
     * us into skipping the existence check).
     */
    protected static synchronized void ensureTestRealm() {
        // Wait for the master-realm admin user to be reachable. The HTTP
        // listener comes up before token issuance is fully wired, so the
        // first few admin-client calls can 401 even though /realms/master
        // already returns 200. Polling until the first request succeeds is
        // cheaper than over-tuning the container's wait strategy.
        Keycloak admin = pollUntilAdminReady();

        // S119: delegate to the production provisioner — same code path
        // that runs at application startup, so the harness gets exactly
        // the realm config that production tenants get. Eats its own dog
        // food; future required-realm-invariants (custom attributes,
        // default client scopes, etc.) are picked up automatically.
        KeycloakAdminProperties props = new KeycloakAdminProperties();
        props.setEnabled(true);
        props.setServerUrl(KEYCLOAK.getAuthServerUrl());
        props.setAdminRealm("master");
        props.setClientId("admin-cli");
        props.setUsername(KEYCLOAK.getAdminUsername());
        props.setPassword(KEYCLOAK.getAdminPassword());
        props.setTargetRealm(TEST_REALM);

        new KeycloakTenantProvisioner(new StaticObjectProviderForTests<>(admin), props)
                .provisionTenantRealm(TEST_REALM);
    }

    /**
     * Inline minimal {@link org.springframework.beans.factory.ObjectProvider}
     * adapter — same as the test-package {@code StaticObjectProvider}, kept
     * here to keep {@link KeycloakItSupport} self-contained (the provisioner
     * is part of production code; this adapter is the test-only wiring
     * needed to construct it without a Spring context).
     */
    private static final class StaticObjectProviderForTests<T>
            implements org.springframework.beans.factory.ObjectProvider<T> {
        private final T value;
        StaticObjectProviderForTests(T value) { this.value = value; }
        @Override public T getObject()                  { return value; }
        @Override public T getObject(Object... args)    { return value; }
        @Override public T getIfAvailable()             { return value; }
        @Override public T getIfUnique()                { return value; }
        @Override public java.util.stream.Stream<T> stream()        { return java.util.stream.Stream.of(value); }
        @Override public java.util.stream.Stream<T> orderedStream() { return java.util.stream.Stream.of(value); }
    }

    private static Keycloak pollUntilAdminReady() {
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
        throw new IllegalStateException("Keycloak admin client did not become ready within 30s", lastErr);
    }

    /**
     * Exposes Keycloak admin-client config so {@code KeycloakAdminConfig}
     * builds a real client against the running container. Password grant
     * against {@code admin-cli} on the master realm — same shape as the
     * docker-compose dev profile.
     */
    @DynamicPropertySource
    static void keycloakProps(DynamicPropertyRegistry registry) {
        registry.add("cia.keycloak.admin.enabled",      () -> "true");
        registry.add("cia.keycloak.admin.server-url",   KEYCLOAK::getAuthServerUrl);
        registry.add("cia.keycloak.admin.admin-realm",  () -> "master");
        registry.add("cia.keycloak.admin.client-id",    () -> "admin-cli");
        registry.add("cia.keycloak.admin.username",     () -> "admin");
        registry.add("cia.keycloak.admin.password",     () -> "admin");
        registry.add("cia.keycloak.admin.target-realm", () -> TEST_REALM);
    }
}
