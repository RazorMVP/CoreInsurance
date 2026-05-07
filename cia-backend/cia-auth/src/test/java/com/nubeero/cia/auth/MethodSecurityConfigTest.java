package com.nubeero.cia.auth;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(classes = {MethodSecurityConfig.class, MethodSecurityConfigTest.TestConfig.class})
@ActiveProfiles("test")
@TestExecutionListeners(
        listeners = {
                DependencyInjectionTestExecutionListener.class,
                WithSecurityContextTestExecutionListener.class
        },
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS
)
class MethodSecurityConfigTest {

    @jakarta.annotation.Resource
    private ProtectedService protectedService;

    @Test
    @WithMockUser(roles = "FINANCE_VIEW")
    void allowsRequiredRole() {
        assertThat(protectedService.financeView()).isEqualTo("finance");
    }

    @Test
    @WithMockUser(roles = "CUSTOMER_VIEW")
    void deniesWrongRole() {
        assertThatThrownBy(() -> protectedService.financeView())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(authorities = "reports:view")
    void allowsPermissionAuthority() {
        assertThat(protectedService.reportsView()).isEqualTo("reports");
    }

    @Test
    @WithMockUser(roles = "REPORTS_VIEW")
    void deniesRoleWhenPermissionAuthorityIsRequired() {
        assertThatThrownBy(() -> protectedService.reportsView())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Configuration
    static class TestConfig {
        @Bean
        ProtectedService protectedService() {
            return new ProtectedService();
        }
    }

    static class ProtectedService {
        @PreAuthorize("hasRole('FINANCE_VIEW')")
        public String financeView() {
            return "finance";
        }

        @PreAuthorize("hasAuthority('reports:view')")
        public String reportsView() {
            return "reports";
        }
    }
}
