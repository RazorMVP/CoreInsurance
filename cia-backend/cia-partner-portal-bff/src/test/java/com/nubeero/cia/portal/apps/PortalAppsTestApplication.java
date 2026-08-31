package com.nubeero.cia.portal.apps;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal {@code @SpringBootConfiguration} fixture so {@code @SpringBootTest} (full context, not
 * a slice) can bootstrap for {@link PortalAppsIT} — this module has no {@code
 * @SpringBootApplication} of its own (that lives in {@code cia-api}, downstream of this module —
 * see {@code PortalAuthTestApplication}'s javadoc for the identical rationale, which this fixture
 * mirrors).
 *
 * <p>Scans everything {@link PortalAppsIT}'s flow needs: this task's own {@code
 * com.nubeero.cia.portal.apps} package, {@code com.nubeero.cia.portal.auth} (session filter +
 * security chain + {@code PortalPrincipal}), {@code com.nubeero.cia.portal.session} (session
 * store, so the IT can seed a session directly), {@code com.nubeero.cia.portal.grant} (the
 * registry entity/repository), and the same three narrow {@code com.nubeero.cia.common.*}
 * sub-packages {@code PortalAuthTestApplication} uses.
 *
 * <p><b>{@code @EntityScan}/{@code @EnableJpaRepositories} explicitly include {@code
 * com.nubeero.cia.partner.app}</b> (cia-partner-api) — {@link PortalAppsService} reads {@code
 * PartnerApp} rows via {@code PartnerAppRepository}, and Spring Boot's default JPA scanning covers
 * only this class's own package + sub-packages, not that sibling module package.
 *
 * <p>Exclude filter mirrors {@code PortalAuthTestApplication}: keeps every sibling
 * {@code *TestApplication} fixture (this one included, redundantly but harmlessly) out of the
 * scanned component set — most importantly {@code RedisPortalSessionStoreTestApplication}, whose
 * {@code @EnableAutoConfiguration(exclude = ...)} would otherwise silently disable JPA/DataSource
 * autoconfiguration for this context too.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {
                "com.nubeero.cia.portal.apps",
                "com.nubeero.cia.portal.auth",
                "com.nubeero.cia.portal.session",
                "com.nubeero.cia.portal.grant",
                "com.nubeero.cia.common.tenant",
                "com.nubeero.cia.common.exception",
                "com.nubeero.cia.common.config"
        },
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*TestApplication$")
)
@EntityScan(basePackages = {"com.nubeero.cia.portal.grant", "com.nubeero.cia.partner.app"})
@EnableJpaRepositories(basePackages = {"com.nubeero.cia.portal.grant", "com.nubeero.cia.partner.app"})
class PortalAppsTestApplication {
}
