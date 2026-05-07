package com.nubeero.cia.tenant;

import com.nubeero.cia.auth.MethodSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TenantProvisioningController.class)
@ContextConfiguration(classes = {
        TenantProvisioningController.class,
        MethodSecurityConfig.class,
        TenantProvisioningControllerAuthorizationTest.TestConfig.class
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
class TenantProvisioningControllerAuthorizationTest {

    private static final String VALID_REQUEST = """
            {
              "schemaName": "tenant_alpha",
              "subdomain": "alpha",
              "name": "Alpha Insurance"
            }
            """;

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @jakarta.annotation.Resource
    private StubTenantProvisioningService tenantProvisioningService;

    @BeforeEach
    void resetStub() {
        tenantProvisioningService.reset();
    }

    @Test
    @WithMockUser(roles = "PLATFORM_ADMIN")
    void allowsPlatformAdminToProvisionTenant() throws Exception {
        mockMvc.perform(post("/admin/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.schemaName").value("tenant_alpha"))
                .andExpect(jsonPath("$.data.subdomain").value("alpha"))
                .andExpect(jsonPath("$.data.active").value(true));

        assertThat(tenantProvisioningService.callCount()).isEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "SETUP_UPDATE")
    void rejectsNonPlatformAdminBeforeProvisioning() throws Exception {
        mockMvc.perform(post("/admin/v1/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(tenantProvisioningService.callCount()).isZero();
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        StubTenantProvisioningService tenantProvisioningService() {
            return new StubTenantProvisioningService();
        }
    }

    static class StubTenantProvisioningService extends TenantProvisioningService {
        private final AtomicInteger callCount = new AtomicInteger();

        StubTenantProvisioningService() {
            super(null, null);
        }

        @Override
        public TenantProvisionResponse provision(TenantProvisionRequest request) {
            callCount.incrementAndGet();
            return new TenantProvisionResponse(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    request.schemaName(),
                    request.subdomain(),
                    request.name(),
                    true
            );
        }

        int callCount() {
            return callCount.get();
        }

        void reset() {
            callCount.set(0);
        }
    }
}
