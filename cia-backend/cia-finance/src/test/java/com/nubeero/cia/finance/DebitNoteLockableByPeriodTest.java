package com.nubeero.cia.finance;

import com.nubeero.cia.common.entity.BaseEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 1.7b contract test for {@link DebitNote}. Lock date is
 * {@code BaseEntity.createdAt} converted to LocalDate in UTC; null for
 * a fresh unsaved row so the interceptor ALLOWs the first insert (the
 * JPA listener sets createdAt during @PrePersist, before Hibernate's
 * onSave fires the interceptor — but for safety the contract permits
 * null).
 */
class DebitNoteLockableByPeriodTest {

    @Test
    @DisplayName("getLockDate is null for an unsaved DebitNote — ALLOWs the insert")
    void getLockDateNullForUnsaved() {
        assertThat(new DebitNote().getLockDate()).isNull();
    }

    @Test
    @DisplayName("getLockDate reflects createdAt once persisted")
    void getLockDateReflectsCreatedAt() throws Exception {
        DebitNote dn = new DebitNote();
        setCreatedAt(dn, Instant.parse("2026-05-15T10:00:00Z"));
        assertThat(dn.getLockDate())
            .isEqualTo(LocalDate.of(2026, 5, 15))
            .as("UTC conversion is the documented contract")
            .isEqualTo(Instant.parse("2026-05-15T10:00:00Z").atOffset(ZoneOffset.UTC).toLocalDate());
    }

    @Test
    @DisplayName("isReversal defaults to false — DebitNote has no reversal mechanism today")
    void isReversalDefaultsFalse() {
        assertThat(new DebitNote().isReversal()).isFalse();
    }

    // BaseEntity.createdAt is normally set by Spring Data's auditing listener
    // at @PrePersist. In a unit test there is no JPA lifecycle running, so we
    // reflectively poke the field to simulate the post-persist state.
    private static void setCreatedAt(Object entity, Instant value) throws Exception {
        Field field = BaseEntity.class.getDeclaredField("createdAt");
        field.setAccessible(true);
        field.set(entity, value);
    }
}
