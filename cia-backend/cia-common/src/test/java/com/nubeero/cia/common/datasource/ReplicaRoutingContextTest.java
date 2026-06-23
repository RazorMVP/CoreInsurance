package com.nubeero.cia.common.datasource;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicaRoutingContextTest {

    @Test
    void notPreferredByDefault() {
        assertThat(ReplicaRoutingContext.isReplicaPreferred()).isFalse();
    }

    @Test
    void preferredInsideOnReplica_clearedAfter() {
        boolean[] inside = {false};
        ReplicaRoutingContext.onReplica(() -> {
            inside[0] = ReplicaRoutingContext.isReplicaPreferred();
            return null;
        });
        assertThat(inside[0]).as("preferred inside").isTrue();
        assertThat(ReplicaRoutingContext.isReplicaPreferred()).as("cleared after").isFalse();
    }

    @Test
    void reentrant_innerCallDoesNotClearOuter() {
        boolean[] stillPreferredAfterInner = {false};
        ReplicaRoutingContext.onReplica(() -> {
            ReplicaRoutingContext.onReplica(() -> null); // nested — must not clear the outer flag
            stillPreferredAfterInner[0] = ReplicaRoutingContext.isReplicaPreferred();
            return null;
        });
        assertThat(stillPreferredAfterInner[0]).isTrue();
        assertThat(ReplicaRoutingContext.isReplicaPreferred()).isFalse();
    }

    @Test
    void flagIsClearedEvenWhenActionThrows() {
        assertThatThrownBy(() -> ReplicaRoutingContext.onReplica(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(ReplicaRoutingContext.isReplicaPreferred()).isFalse();
    }

    @Test
    void returnsTheActionResult() {
        assertThat(ReplicaRoutingContext.onReplica(() -> "ok")).isEqualTo("ok");
    }
}
