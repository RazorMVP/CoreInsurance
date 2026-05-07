package com.nubeero.cia.reports.controller;

import com.nubeero.cia.auth.MethodSecurityConfig;
import com.nubeero.cia.reports.domain.ReportCategory;
import com.nubeero.cia.reports.domain.ReportDefinition;
import com.nubeero.cia.reports.domain.ReportAccessPolicy;
import com.nubeero.cia.reports.service.ReportAccessService;
import com.nubeero.cia.reports.service.ReportDefinitionService;
import com.nubeero.cia.reports.service.ReportRunnerService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReportController.class)
@ContextConfiguration(classes = {
        ReportController.class,
        MethodSecurityConfig.class,
        ReportControllerAuthorizationTest.TestConfig.class
})
@ActiveProfiles("test")
@TestExecutionListeners(
        listeners = {
                ServletTestExecutionListener.class,
                DependencyInjectionTestExecutionListener.class,
                WithSecurityContextTestExecutionListener.class
        },
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS
)
class ReportControllerAuthorizationTest {

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @Test
    @WithMockUser(authorities = "reports:view")
    void allowsReportDefinitionListWithRequiredAuthority() throws Exception {
        mockMvc.perform(get("/api/v1/reports/definitions"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "FINANCE_VIEW")
    void rejectsReportDefinitionListWithWrongRole() throws Exception {
        mockMvc.perform(get("/api/v1/reports/definitions"))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        ReportDefinitionService reportDefinitionService() {
            return new EmptyReportDefinitionService();
        }

        @Bean
        ReportRunnerService reportRunnerService() {
            return new UnusedReportRunnerService();
        }

        @Bean
        ReportAccessService reportAccessService() {
            return new UnusedReportAccessService();
        }
    }

    private static class EmptyReportDefinitionService extends ReportDefinitionService {
        EmptyReportDefinitionService() {
            super(null);
        }

        @Override
        public List<ReportDefinition> listAll() {
            return List.of();
        }

        @Override
        public List<ReportDefinition> listByCategory(ReportCategory category) {
            return List.of();
        }
    }

    private static class UnusedReportRunnerService extends ReportRunnerService {
        UnusedReportRunnerService() {
            super(null, null, null, null, null);
        }
    }

    private static class UnusedReportAccessService extends ReportAccessService {
        UnusedReportAccessService() {
            super(null, null);
        }

        @Override
        public List<ReportAccessPolicy> listByGroup(UUID accessGroupId) {
            return List.of();
        }
    }
}
