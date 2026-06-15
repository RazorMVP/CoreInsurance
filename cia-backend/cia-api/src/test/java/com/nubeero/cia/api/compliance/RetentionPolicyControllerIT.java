package com.nubeero.cia.api.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.compliance.retention.DataRetentionPolicyRepository;
import com.nubeero.cia.compliance.retention.RetentionPolicyRequest;
import com.nubeero.cia.compliance.retention.RetentionPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({CiaCommonAutoConfiguration.class, RetentionPolicyService.class})
class RetentionPolicyControllerIT extends ComplianceItSupport {

    @Autowired DataRetentionPolicyRepository repository;
    @Autowired RetentionPolicyService service;

    @Test
    void getOrCreate_lazilyCreatesWithDefaults() {
        var p = service.getOrCreate();
        assertThat(p.getCustomerPiiRetentionDays()).isEqualTo(2555);
        assertThat(p.isPurgeEnabled()).isFalse();
        assertThat(p.getPurgeFrequency()).isEqualTo("WEEKLY");
        assertThat(p.getPurgeHourUtc()).isEqualTo(3);
        assertThat(repository.findFirstByDeletedAtIsNull()).isPresent();
    }

    @Test
    void getOrCreate_isIdempotentSingleton() {
        var a = service.getOrCreate();
        var b = service.getOrCreate();
        assertThat(b.getId()).isEqualTo(a.getId());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void update_appliesValidValues() {
        service.update(new RetentionPolicyRequest(365, true, "MONTHLY", 0, 2));
        var p = service.getOrCreate();
        assertThat(p.getCustomerPiiRetentionDays()).isEqualTo(365);
        assertThat(p.isPurgeEnabled()).isTrue();
        assertThat(p.getPurgeFrequency()).isEqualTo("MONTHLY");
        assertThat(p.getPurgeHourUtc()).isEqualTo(2);
    }

    @Test
    void update_rejectsInvalid() {
        assertThatThrownBy(() -> service.update(new RetentionPolicyRequest(2555, true, "DAILY", 0, 3)))
                .isInstanceOf(BusinessRuleException.class);
    }
}
