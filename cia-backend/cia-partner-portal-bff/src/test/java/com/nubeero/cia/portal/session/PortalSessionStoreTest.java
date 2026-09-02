package com.nubeero.cia.portal.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the contract every {@link PortalSessionStore} implementation must satisfy,
 * exercised here against {@link InMemoryPortalSessionStore}. {@code RedisPortalSessionStoreIT}
 * re-runs the same contract against the Redis-backed implementation.
 */
class PortalSessionStoreTest {

    private PortalSessionStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryPortalSessionStore();
    }

    private static PortalSession newSession(Instant idleExpiry, Instant absoluteExpiry) {
        return new PortalSession(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "dev@insurtech.example",
                "Dev One",
                "access-token-abc",
                "refresh-token-xyz",
                absoluteExpiry,
                idleExpiry,
                "csrf-token-123");
    }

    @Test
    void create_thenGet_roundTrips() {
        PortalSession session = newSession(Instant.now().plusSeconds(900), Instant.now().plusSeconds(28_800));

        String id = store.create(session);

        assertThat(id).isEqualTo(session.id());
        Optional<PortalSession> found = store.get(id);
        assertThat(found).contains(session);
    }

    @Test
    void get_missingId_returnsEmpty() {
        assertThat(store.get(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void get_afterAbsoluteExpiry_returnsEmpty() {
        // idleExpiry is still far in the future — only the absolute cutoff has passed.
        PortalSession session = newSession(Instant.now().plusSeconds(900), Instant.now().minusSeconds(1));
        store.create(session);

        assertThat(store.get(session.id())).isEmpty();
    }

    @Test
    void get_afterIdleExpiry_returnsEmpty() {
        // absoluteExpiry is still far in the future — only the idle cutoff has passed.
        PortalSession session = newSession(Instant.now().minusSeconds(1), Instant.now().plusSeconds(28_800));
        store.create(session);

        assertThat(store.get(session.id())).isEmpty();
    }

    @Test
    void touch_extendsIdleExpiry_cappedAtAbsolute() {
        Instant idle = Instant.now().plusSeconds(60);
        Instant absolute = Instant.now().plusSeconds(28_800);
        PortalSession session = newSession(idle, absolute);
        store.create(session);

        store.touch(session.id());

        PortalSession touched = store.get(session.id()).orElseThrow();
        assertThat(touched.idleExpiry()).isAfter(idle);
        assertThat(touched.idleExpiry()).isBeforeOrEqualTo(absolute);
    }

    @Test
    void touch_capsIdleExpiryAtAbsoluteExpiry() {
        // absoluteExpiry is sooner than a full IDLE_TTL away — touch must not push idleExpiry past it.
        Instant absolute = Instant.now().plusSeconds(30);
        PortalSession session = newSession(Instant.now().plusSeconds(10), absolute);
        store.create(session);

        store.touch(session.id());

        PortalSession touched = store.get(session.id()).orElseThrow();
        assertThat(touched.idleExpiry()).isEqualTo(absolute);
    }

    @Test
    void touch_onMissingId_isNoOp() {
        store.touch(UUID.randomUUID().toString());
        // no exception — nothing to assert beyond "didn't throw"
    }

    @Test
    void delete_removesSession() {
        PortalSession session = newSession(Instant.now().plusSeconds(900), Instant.now().plusSeconds(28_800));
        store.create(session);

        store.delete(session.id());

        assertThat(store.get(session.id())).isEmpty();
    }

    @Test
    void delete_onMissingId_isNoOp() {
        store.delete(UUID.randomUUID().toString());
        // no exception — nothing to assert beyond "didn't throw"
    }
}
