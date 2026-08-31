package com.nubeero.cia.portal.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.auth.KeycloakProperties;
import com.nubeero.cia.auth.PartnerPortalRealmProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wires the BFF's OAuth client for the {@code partner} realm.
 *
 * <p>{@link PartnerPortalRealmProperties} (Task 2, {@code cia-auth}) is declared with
 * {@code @ConfigurationProperties} only — no {@code @Configuration}/{@code @Component} of its own
 * — so it was never registered as a bean anywhere until now. This is that registration.
 * {@link KeycloakProperties} is already self-registering ({@code @Configuration +
 * @ConfigurationProperties}) but lives in {@code cia-auth}, a package this module's narrow
 * {@code @ComponentScan} in tests does not sweep — {@code @Import} pulls it in explicitly instead
 * of widening that scan to the rest of {@code com.nubeero.cia.auth} (which would drag in the
 * unrelated prod JWT resource-server stack).
 *
 * <p>The real {@link KeycloakPortalOAuthClient} bean below is plain (not {@code @Primary}) —
 * {@code PortalAuthFlowIT} overrides it with a {@code @Primary} stub bean rather than relying on
 * {@code @ConditionalOnMissingBean} ordering, which is not guaranteed against a plain
 * {@code @TestConfiguration} (only against genuine auto-configuration classes). Marking this bean
 * unconditional keeps exactly one candidate primary at a time: the real one when no test override
 * is present, the stub when there is.
 */
@Configuration
@EnableConfigurationProperties(PartnerPortalRealmProperties.class)
@Import(KeycloakProperties.class)
public class PortalOAuthConfig {

    @Bean
    public PortalOAuthClient portalOAuthClient(
            KeycloakProperties keycloakProperties,
            PartnerPortalRealmProperties portalProperties,
            ObjectMapper objectMapper) {
        return new KeycloakPortalOAuthClient(keycloakProperties, portalProperties, objectMapper);
    }
}
