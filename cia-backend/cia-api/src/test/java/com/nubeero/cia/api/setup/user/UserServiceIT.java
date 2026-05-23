package com.nubeero.cia.api.setup.user;

import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.setup.access.AccessGroup;
import com.nubeero.cia.setup.access.AccessGroupPermission;
import com.nubeero.cia.setup.access.AccessGroupRepository;
import com.nubeero.cia.setup.user.KeycloakAdminProperties;
import com.nubeero.cia.setup.user.UserService;
import com.nubeero.cia.setup.user.UserStatus;
import com.nubeero.cia.setup.user.dto.UserRequest;
import com.nubeero.cia.setup.user.dto.UserResponse;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F1e-IT — end-to-end Testcontainers IT for {@link UserService} against a
 * real Keycloak admin client.
 *
 * <p>Fixture: shared static {@link KeycloakContainer} (Keycloak 24.0 on
 * port 8080 → mapped to a host port) + standard postgres container for the
 * AccessGroupRepository. A {@code test} realm is created at class init;
 * each test creates + deletes its own users so they don't leak across
 * methods.
 *
 * <p>Coverage:
 * <ol>
 *   <li>create user + role sync against an empty realm (auto-creates roles)</li>
 *   <li>list / get / get-not-found</li>
 *   <li>update profile (rename, no group change → roles untouched)</li>
 *   <li>update with access-group switch → role sync swaps the realm-role set</li>
 *   <li>deactivate → enabled=false; activate → enabled=true</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CiaCommonAutoConfiguration.class)
@Testcontainers
class UserServiceIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("cia").withUsername("cia").withPassword("cia_dev");

    @Container
    static final KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:24.0");

    private static final String TEST_REALM = "test";

    @Autowired AccessGroupRepository accessGroupRepository;

    Keycloak     adminClient;
    UserService  userService;

    static {
        // Testcontainers reuses static @Container references across the
        // JUnit lifecycle automatically.
    }

    @org.springframework.test.context.DynamicPropertySource
    static void registerProps(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.flyway.enabled",      () -> "true");
        r.add("spring.flyway.locations",    () -> "classpath:db/migration");
        // Pin migrations to V3 so the cia-setup access_group + permission
        // tables exist without dragging in finance/closures migrations
        // (which would require the wider schema and inflate the test).
        r.add("spring.flyway.target",       () -> "3");
        r.add("cia.security.pii-key",       () -> "test-key-for-it-only-not-secret");
    }

    @BeforeAll
    static void bootstrap() {
        // Create the `test` realm once per JVM. Keycloak's master realm
        // ships with the `admin-cli` public client which we use via
        // password grant against the master admin (admin/admin).
        Keycloak boot = KeycloakBuilder.builder()
                .serverUrl(keycloak.getAuthServerUrl())
                .realm("master")
                .clientId("admin-cli")
                .grantType("password")
                .username(keycloak.getAdminUsername())
                .password(keycloak.getAdminPassword())
                .build();
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(TEST_REALM);
        realm.setEnabled(true);
        try {
            boot.realms().create(realm);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            // Already exists from a previous test run — fine.
            if (e.getResponse().getStatus() != 409) throw e;
        }
    }

    @BeforeEach
    void setUp() {
        adminClient = KeycloakBuilder.builder()
                .serverUrl(keycloak.getAuthServerUrl())
                .realm("master")
                .clientId("admin-cli")
                .grantType("password")
                .username(keycloak.getAdminUsername())
                .password(keycloak.getAdminPassword())
                .build();

        KeycloakAdminProperties props = new KeycloakAdminProperties();
        props.setEnabled(true);
        props.setTargetRealm(TEST_REALM);

        // Adapt the real Keycloak instance into the ObjectProvider the
        // service expects (production wiring uses Spring's autowired
        // provider; in tests we hand-roll a minimal one).
        ObjectProvider<Keycloak> provider = new StaticObjectProvider<>(adminClient);

        userService = new UserService(provider, props, accessGroupRepository);
    }

    @Test
    @DisplayName("create user → realm roles auto-synced from access group permissions")
    void create_syncsRealmRoles() {
        AccessGroup group = seedGroup("Underwriters", Set.of("SETUP_VIEW", "POLICY_CREATE"));

        UserRequest req = userRequest("alice@cia.test", "Alice", "Underwriter", group.getId());
        UserResponse created = userService.create(req);

        assertThat(created.getId()).isNotBlank();
        assertThat(created.getEmail()).isEqualTo("alice@cia.test");
        assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(created.getAccessGroupId()).isEqualTo(group.getId().toString());
        assertThat(created.getAccessGroupName()).isEqualTo("Underwriters");

        List<String> roles = realmRoleNames(created.getId());
        assertThat(roles).contains("SETUP_VIEW", "POLICY_CREATE");
    }

    @Test
    @DisplayName("get not-found → ResourceNotFoundException")
    void get_notFound() {
        assertThatThrownBy(() -> userService.get(UUID.randomUUID().toString()))
                .isInstanceOf(com.nubeero.cia.common.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("list includes every user in the realm")
    void list_returnsAll() {
        AccessGroup group = seedGroup("Brokers", Set.of("QUOTATION_VIEW"));
        userService.create(userRequest("bob@cia.test", "Bob", "Broker", group.getId()));
        userService.create(userRequest("carol@cia.test", "Carol", "Broker", group.getId()));

        List<UserResponse> all = userService.list();
        assertThat(all).extracting(UserResponse::getEmail)
                .contains("bob@cia.test", "carol@cia.test");
    }

    @Test
    @DisplayName("update profile-only → roles untouched, names changed")
    void update_profileOnly() {
        AccessGroup group = seedGroup("Claims", Set.of("CLAIMS_VIEW"));
        UserResponse u = userService.create(userRequest("dave@cia.test", "Dave", "Claims", group.getId()));
        Set<String> rolesBefore = new HashSet<>(realmRoleNames(u.getId()));

        UserRequest patch = userRequest("dave@cia.test", "Dave", "Renamed", group.getId());
        patch.setStatus(UserStatus.ACTIVE);
        UserResponse updated = userService.update(u.getId(), patch);

        assertThat(updated.getLastName()).isEqualTo("Renamed");
        Set<String> rolesAfter = new HashSet<>(realmRoleNames(u.getId()));
        assertThat(rolesAfter).isEqualTo(rolesBefore);
    }

    @Test
    @DisplayName("update with access-group switch → role-set swaps")
    void update_switchAccessGroup() {
        AccessGroup viewers = seedGroup("Viewers", Set.of("SETUP_VIEW"));
        AccessGroup editors = seedGroup("Editors", Set.of("SETUP_VIEW", "SETUP_UPDATE"));

        UserResponse u = userService.create(userRequest("eve@cia.test", "Eve", "User", viewers.getId()));
        assertThat(realmRoleNames(u.getId())).contains("SETUP_VIEW")
                .doesNotContain("SETUP_UPDATE");

        UserRequest patch = userRequest("eve@cia.test", "Eve", "User", editors.getId());
        patch.setStatus(UserStatus.ACTIVE);
        userService.update(u.getId(), patch);

        assertThat(realmRoleNames(u.getId())).contains("SETUP_VIEW", "SETUP_UPDATE");
    }

    @Test
    @DisplayName("deactivate + activate flips Keycloak enabled flag")
    void deactivate_activate() {
        AccessGroup group = seedGroup("Anything", Set.of("SETUP_VIEW"));
        UserResponse u = userService.create(userRequest("frank@cia.test", "Frank", "User", group.getId()));

        UserResponse deactivated = userService.deactivate(u.getId());
        assertThat(deactivated.getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(adminClient.realm(TEST_REALM).users().get(u.getId()).toRepresentation().isEnabled())
                .isFalse();

        UserResponse activated = userService.activate(u.getId());
        assertThat(activated.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(adminClient.realm(TEST_REALM).users().get(u.getId()).toRepresentation().isEnabled())
                .isTrue();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private AccessGroup seedGroup(String name, Set<String> permissions) {
        AccessGroup group = AccessGroup.builder()
                .name(name + "-" + UUID.randomUUID().toString().substring(0, 8))
                .description("test-only")
                .build();
        AccessGroup saved = accessGroupRepository.save(group);
        for (String p : permissions) {
            AccessGroupPermission ap = AccessGroupPermission.builder()
                    .accessGroup(saved).permission(p).build();
            saved.getPermissions().add(ap);
        }
        return accessGroupRepository.save(saved);
    }

    private UserRequest userRequest(String email, String first, String last, UUID groupId) {
        UserRequest req = new UserRequest();
        req.setEmail(email);
        req.setFirstName(first);
        req.setLastName(last);
        req.setAccessGroupId(groupId);
        return req;
    }

    private List<String> realmRoleNames(String userId) {
        List<RoleRepresentation> roles = adminClient.realm(TEST_REALM).users().get(userId)
                .roles().realmLevel().listAll();
        List<String> names = new ArrayList<>();
        for (RoleRepresentation r : roles) names.add(r.getName());
        return names;
    }

    /**
     * Minimal ObjectProvider that always returns the same instance — replaces
     * Spring's autowired version in the test setup. Most ObjectProvider
     * methods aren't used by UserService; only {@code getIfAvailable()} is.
     */
    private static final class StaticObjectProvider<T> implements ObjectProvider<T> {
        private final T instance;
        StaticObjectProvider(T instance) { this.instance = instance; }
        @Override public T getObject() { return instance; }
        @Override public T getObject(Object... args) { return instance; }
        @Override public T getIfAvailable() { return instance; }
        @Override public T getIfUnique() { return instance; }
    }
}
