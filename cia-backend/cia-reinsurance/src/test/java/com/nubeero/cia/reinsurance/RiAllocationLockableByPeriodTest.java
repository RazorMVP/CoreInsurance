package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.entity.BaseEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Slice 1.7b contract test for {@link RiAllocation}. */
class RiAllocationLockableByPeriodTest {

    @Test
    @DisplayName("getLockDate null for unsaved; reflects createdAt once set")
    void getLockDateContract() throws Exception {
        assertThat(new RiAllocation().getLockDate()).isNull();

        RiAllocation alloc = new RiAllocation();
        setCreatedAt(alloc, Instant.parse("2026-05-15T10:00:00Z"));
        assertThat(alloc.getLockDate()).isEqualTo(LocalDate.of(2026, 5, 15));
    }

    @Test
    @DisplayName("isReversal defaults to false")
    void isReversalDefaultsFalse() {
        assertThat(new RiAllocation().isReversal()).isFalse();
    }

    private static void setCreatedAt(Object entity, Instant value) throws Exception {
        Field field = BaseEntity.class.getDeclaredField("createdAt");
        field.setAccessible(true);
        field.set(entity, value);
    }
}
