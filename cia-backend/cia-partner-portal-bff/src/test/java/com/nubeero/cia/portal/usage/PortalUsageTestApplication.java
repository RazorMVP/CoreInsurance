package com.nubeero.cia.portal.usage;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal {@code @SpringBootConfiguration} fixture so {@code @SpringBootTest} (full context, not a
 * slice) can bootstrap for {@link PortalUsageIT} — this module has no {@code
 * @SpringBootApplication} of its own (see {@code PortalAppsTestApplication}'s javadoc for the
 * identical rationale, which this fixture mirrors).
 *
 * <p>Adds this task's own {@code com.nubeero.cia.portal.usage} (controller/service/DTOs) and
 * {@code com.nubeero.cia.partner.usage} (cia-partner-api — {@code PartnerUsageRollupStore} +
 * {@code PartnerRequestDaily}/repository, the same beans the real {@code
 * PartnerRequestMetricsFilter} writes through) to {@code PortalAppsTestApplication}'s scan set.
 * {@code com.nubeero.cia.partner.webhook} is entity-scanned (not component-scanned — {@link
 * PortalUsageService} reads {@code WebhookRegistration}/{@code WebhookDeliveryLog} via a raw JPQL
 * query through its own dedicated {@code EntityManager}, not via those entities' Spring Data
 * repositories) so Hibernate knows about the two entity classes.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {
                "com.nubeero.cia.portal.usage",
                "com.nubeero.cia.portal.apps",
                "com.nubeero.cia.portal.auth",
                "com.nubeero.cia.portal.session",
                "com.nubeero.cia.portal.grant",
                "com.nubeero.cia.partner.usage",
                "com.nubeero.cia.common.tenant",
                "com.nubeero.cia.common.exception",
                "com.nubeero.cia.common.config"
        },
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*TestApplication$")
)
@EntityScan(basePackages = {
        "com.nubeero.cia.portal.grant",
        "com.nubeero.cia.partner.app",
        "com.nubeero.cia.partner.usage",
        "com.nubeero.cia.partner.webhook"
})
@EnableJpaRepositories(basePackages = {
        "com.nubeero.cia.portal.grant",
        "com.nubeero.cia.partner.app",
        "com.nubeero.cia.partner.usage"
})
class PortalUsageTestApplication {
}
