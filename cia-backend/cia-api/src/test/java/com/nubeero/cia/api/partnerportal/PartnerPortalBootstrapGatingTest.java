package com.nubeero.cia.api.partnerportal;

import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves the @ConditionalOnProperty(enabled=true) gate on PartnerPortalBootstrapRunner:
 * - bean ABSENT when the flag is unset (default false) — the existing IT baseline is safe
 * - bean PRESENT when the flag is explicitly true (with a wired KeycloakTenantProvisioner)
 * - context FAILS FAST when the flag is true but the Keycloak admin client is disabled
 *   (mirrors PlatformBootstrapRunner: KeycloakTenantProvisioner is itself
 *   @ConditionalOnProperty(cia.keycloak.admin.enabled=true), so admin-disabled means that
 *   bean never exists — PartnerPortalBootstrapRunner's constructor dependency on it then
 *   makes Spring context startup fail, not a silent no-op).
 */
class PartnerPortalBootstrapGatingTest {

    private static PartnerPortalBootstrapProperties propsBean() {
        return new PartnerPortalBootstrapProperties(); // bootstrap.enabled=false by default
    }

    private static PartnerPortalBootstrapProperties propsBeanEnabledWithTempPassword() {
        var props = new PartnerPortalBootstrapProperties();
        props.getBootstrap().setEnabled(true);
        props.getBootstrap().setAdminTempPassword("Temp-Pass123!");
        return props;
    }

    private static KeycloakTenantProvisioner provisionerBean() {
        return mock(KeycloakTenantProvisioner.class);
    }

    @Test
    void runnerBeanAbsentWhenFlagUnset() {
        new ApplicationContextRunner()
            .withBean(PartnerPortalBootstrapProperties.class, PartnerPortalBootstrapGatingTest::propsBean)
            .withBean(KeycloakTenantProvisioner.class, PartnerPortalBootstrapGatingTest::provisionerBean)
            .withUserConfiguration(PartnerPortalBootstrapRunner.class)
            // no cia.partner-portal.bootstrap.enabled property → @ConditionalOnProperty havingValue="true" not met
            .run(ctx -> assertThat(ctx).doesNotHaveBean(PartnerPortalBootstrapRunner.class));
    }

    @Test
    void runnerBeanPresentWhenFlagTrue() {
        new ApplicationContextRunner()
            .withBean(PartnerPortalBootstrapProperties.class,
                    PartnerPortalBootstrapGatingTest::propsBeanEnabledWithTempPassword)
            .withBean(KeycloakTenantProvisioner.class, PartnerPortalBootstrapGatingTest::provisionerBean)
            .withUserConfiguration(PartnerPortalBootstrapRunner.class)
            // KeycloakTenantProvisioner itself carries @ConditionalOnProperty(cia.keycloak.admin.enabled),
            // honoured by Spring even for a manually-registered bean (its AnnotatedGenericBeanDefinition
            // metadata is still condition-evaluated) — so the "admin enabled" case must set both flags.
            .withPropertyValues(
                    "cia.partner-portal.bootstrap.enabled=true",
                    "cia.keycloak.admin.enabled=true")
            .run(ctx -> assertThat(ctx).hasSingleBean(PartnerPortalBootstrapRunner.class));
    }

    @Test
    void runnerFailsFastWhenKeycloakAdminDisabled() {
        new ApplicationContextRunner()
            .withBean(PartnerPortalBootstrapProperties.class,
                    PartnerPortalBootstrapGatingTest::propsBeanEnabledWithTempPassword)
            // KeycloakTenantProvisioner intentionally NOT registered — mirrors
            // cia.keycloak.admin.enabled=false, under which that bean's own
            // @ConditionalOnProperty means it never exists in a real application context.
            .withUserConfiguration(PartnerPortalBootstrapRunner.class)
            .withPropertyValues(
                    "cia.partner-portal.bootstrap.enabled=true",
                    "cia.keycloak.admin.enabled=false")
            .run(ctx -> assertThat(ctx).hasFailed());
    }
}
