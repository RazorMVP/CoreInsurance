package com.nubeero.cia.portal.proxy;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal {@code @SpringBootConfiguration} fixture so {@code @SpringBootTest} (full context, not a
 * slice) can bootstrap for {@link PortalProxyIT} — this module has no {@code @SpringBootApplication}
 * of its own (that lives in {@code cia-api}, downstream — see {@code PortalAppsTestApplication}'s
 * javadoc for the identical rationale, which this fixture mirrors).
 *
 * <p>Adds two packages to {@code PortalAppsTestApplication}'s scan set: {@code
 * com.nubeero.cia.portal.token} (the {@link com.nubeero.cia.portal.token.PartnerAppTokenService}
 * this task mints tokens through) and {@code com.nubeero.cia.portal.proxy} (this task's own
 * controller/service/client).
 *
 * <p><b>The {@code Keycloak.*} exclude filter is load-bearing.</b> {@code
 * com.nubeero.cia.portal.token} also contains three PRODUCTION Keycloak-admin-backed classes
 * ({@code KeycloakAdminPartnerClientSecretResolver}, {@code KeycloakClientCredentialsTokenGrantor},
 * {@code KeycloakPartnerAppSecretRotator}) whose constructors need beans this minimal context never
 * provides ({@code KeycloakProperties} lives in {@code com.nubeero.cia.auth}, deliberately NOT
 * scanned here — pulling that package in drags in the full JWT-resource-server / multi-chain
 * security wiring {@code PortalSecurityConfig} is meant to stay independent of, mirroring why
 * {@code PortalAppsTestApplication} never scans it either). {@link PortalProxyIT} supplies {@code
 * @Primary} test-double beans for the three seam INTERFACES ({@code PartnerClientSecretResolver},
 * {@code ClientCredentialsTokenGrantor}, {@code PartnerAppSecretRotator}) instead — the excluded
 * concrete classes are simply never candidates.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {
                "com.nubeero.cia.portal.apps",
                "com.nubeero.cia.portal.auth",
                "com.nubeero.cia.portal.session",
                "com.nubeero.cia.portal.grant",
                "com.nubeero.cia.portal.token",
                "com.nubeero.cia.portal.proxy",
                "com.nubeero.cia.common.tenant",
                "com.nubeero.cia.common.exception",
                "com.nubeero.cia.common.config"
        },
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*TestApplication$"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.token\\.Keycloak.*")
        }
)
@EntityScan(basePackages = {"com.nubeero.cia.portal.grant", "com.nubeero.cia.partner.app"})
@EnableJpaRepositories(basePackages = {"com.nubeero.cia.portal.grant", "com.nubeero.cia.partner.app"})
class PortalProxyTestApplication {
}
