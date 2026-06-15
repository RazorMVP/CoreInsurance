package com.nubeero.cia.compliance.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nubeero.cia.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

class RetentionPolicyValidationTest {

    private final RetentionPolicyService service = new RetentionPolicyService(null);  // validate() needs no repo

    @Test
    void rejectsNonPositiveRetentionDays() {
        assertThatThrownBy(() -> service.validate(
                new RetentionPolicyRequest(0, false, "WEEKLY", 0, 3)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("retention");
    }

    @Test
    void rejectsUnknownFrequency() {
        assertThatThrownBy(() -> service.validate(
                new RetentionPolicyRequest(2555, true, "DAILY", 0, 3)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("frequency");
    }

    @Test
    void rejectsDayOfWeekOutOfRange() {
        assertThatThrownBy(() -> service.validate(
                new RetentionPolicyRequest(2555, true, "WEEKLY", 7, 3)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("day");
    }

    @Test
    void rejectsHourOutOfRange() {
        assertThatThrownBy(() -> service.validate(
                new RetentionPolicyRequest(2555, true, "WEEKLY", 0, 24)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("hour");
    }

    @Test
    void acceptsValidRequest() {
        service.validate(new RetentionPolicyRequest(365, true, "MONTHLY", 0, 2));  // no throw
        assertThat(true).isTrue();
    }
}
