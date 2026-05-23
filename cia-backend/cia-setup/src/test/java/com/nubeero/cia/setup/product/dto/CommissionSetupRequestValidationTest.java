package com.nubeero.cia.setup.product.dto;

import com.nubeero.cia.setup.product.CommissionSourceType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function tests for the {@link CommissionSetupRequest} cross-field
 * validator. Mirrors {@code KeycloakPolicyDslTest}'s style — no Spring, no
 * Hibernate, just the Bean Validation API against a freshly built validator
 * factory. Runs in single-millisecond range and adds zero IT-suite cost.
 *
 * <p>D1 (server-side date-range defence). The frontend already enforces this
 * via zod, but the back-end has to refuse an invalid range too — silent
 * insertion of a row where {@code effectiveTo &lt; effectiveFrom} would
 * corrupt commission scheduling.
 */
class CommissionSetupRequestValidationTest {

    private static ValidatorFactory FACTORY;
    private static Validator VALIDATOR;

    @BeforeAll
    static void setUp() {
        FACTORY = Validation.buildDefaultValidatorFactory();
        VALIDATOR = FACTORY.getValidator();
    }

    @AfterAll
    static void tearDown() {
        FACTORY.close();
    }

    private static CommissionSetupRequest base() {
        CommissionSetupRequest r = new CommissionSetupRequest();
        r.setCommissionSource(CommissionSourceType.BROKER);
        r.setRate(new BigDecimal("10.00"));
        r.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        return r;
    }

    @Test
    @DisplayName("valid when effectiveTo is after effectiveFrom")
    void validWhenEndAfterStart() {
        CommissionSetupRequest r = base();
        r.setEffectiveTo(LocalDate.of(2026, 12, 31));
        assertThat(VALIDATOR.validate(r)).isEmpty();
    }

    @Test
    @DisplayName("valid when effectiveTo equals effectiveFrom (single-day window)")
    void validWhenEndEqualsStart() {
        CommissionSetupRequest r = base();
        r.setEffectiveTo(LocalDate.of(2026, 1, 1));
        assertThat(VALIDATOR.validate(r)).isEmpty();
    }

    @Test
    @DisplayName("valid when effectiveTo is null (open-ended commission rule)")
    void validWhenEndNull() {
        CommissionSetupRequest r = base();
        r.setEffectiveTo(null);
        assertThat(VALIDATOR.validate(r)).isEmpty();
    }

    @Test
    @DisplayName("INVALID when effectiveTo is before effectiveFrom")
    void invalidWhenEndBeforeStart() {
        CommissionSetupRequest r = base();
        r.setEffectiveTo(LocalDate.of(2025, 12, 31));

        Set<ConstraintViolation<CommissionSetupRequest>> violations = VALIDATOR.validate(r);
        assertThat(violations).hasSize(1);
        ConstraintViolation<CommissionSetupRequest> v = violations.iterator().next();
        assertThat(v.getMessage()).isEqualTo("effectiveTo must be on or after effectiveFrom");
        // The constraint reports its property as the @AssertTrue method's
        // derived name — useful proof that the right validator fired.
        assertThat(v.getPropertyPath().toString()).isEqualTo("dateRangeValid");
    }
}
