package com.nubeero.cia.finance;

import com.nubeero.cia.common.entity.BaseEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Slice 1.7b — same shape as {@link DebitNoteLockableByPeriodTest}. */
class CreditNoteLockableByPeriodTest {

    @Test
    @DisplayName("getLockDate null for unsaved; reflects createdAt once set")
    void getLockDateContract() throws Exception {
        assertThat(new CreditNote().getLockDate()).isNull();

        CreditNote cn = new CreditNote();
        setCreatedAt(cn, Instant.parse("2026-05-15T10:00:00Z"));
        assertThat(cn.getLockDate()).isEqualTo(LocalDate.of(2026, 5, 15));
    }

    @Test
    @DisplayName("isReversal defaults to false")
    void isReversalDefaultsFalse() {
        assertThat(new CreditNote().isReversal()).isFalse();
    }

    private static void setCreatedAt(Object entity, Instant value) throws Exception {
        Field field = BaseEntity.class.getDeclaredField("createdAt");
        field.setAccessible(true);
        field.set(entity, value);
    }
}
