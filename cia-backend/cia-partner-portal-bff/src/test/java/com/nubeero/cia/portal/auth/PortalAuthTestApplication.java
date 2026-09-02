package com.nubeero.cia.portal.auth;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal {@code @SpringBootConfiguration} fixture so {@code @SpringBootTest} (full context, not
 * a slice) can bootstrap for {@code PortalAuthFlowIT} — this module has no {@code
 * @SpringBootApplication} of its own (that lives in {@code cia-api}, which is downstream of this
 * module, not upstream — see the module dependency direction in {@code CLAUDE.md} §3).
 *
 * <p>Deliberately scoped to only the packages this flow actually needs
 * ({@code com.nubeero.cia.portal.{auth,session,grant}} + three narrow
 * {@code com.nubeero.cia.common.*} sub-packages) rather than a broad {@code com.nubeero.cia.portal}
 * or {@code com.nubeero.cia.auth} sweep — the sibling
 * {@code com.nubeero.cia.portal.developer} package pulls in {@code PartnerAppService}
 * (cia-partner-api), which drags in Redis/bucket4j rate-limiting and webhook machinery this IT has
 * no need to satisfy, and {@code com.nubeero.cia.auth} carries the prod JWT resource-server chain
 * ({@code SecurityConfig}) this IT deliberately does not exercise (see {@code
 * PortalSecurityConfig}'s javadoc on multi-chain coexistence — proven by code review /
 * {@code @Order} + distinct {@code securityMatcher}, not by a live IT in this narrow module).
 *
 * <p><b>{@code @EntityScan}/{@code @EnableJpaRepositories} explicit, not inferred:</b> Spring
 * Boot's default JPA entity/repository scanning covers only the package of the
 * {@code @SpringBootConfiguration} class (here, {@code com.nubeero.cia.portal.auth}) and its
 * sub-packages — {@code com.nubeero.cia.portal.grant} (holding {@code PartnerPortalGrant} + its
 * repository) is a *sibling*, not a sub-package, so it would silently be missed without these two
 * annotations naming it explicitly.
 *
 * <p><b>Exclude filter is load-bearing, not tidiness:</b> {@code com.nubeero.cia.portal.session}
 * (in scope, for the real {@link com.nubeero.cia.portal.session.PortalSessionStore} bean) also
 * carries {@code RedisPortalSessionStoreTestApplication} — a sibling test fixture whose
 * {@code @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, ...})} would
 * otherwise be swept in by this scan too. Spring merges every {@code @EnableAutoConfiguration}
 * found anywhere in the context into one shared exclusion set — sweeping that fixture in silently
 * disables JPA/DataSource autoconfiguration for *this* context as well, and {@code
 * PartnerPortalGrantRepository} fails to wire with "No bean named 'entityManagerFactory'
 * available". The {@code *TestApplication} name-pattern filter keeps every such fixture
 * (this one included, redundantly but harmlessly, since it's the root config already) out of the
 * scanned component set.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {
                "com.nubeero.cia.portal.auth",
                "com.nubeero.cia.portal.session",
                "com.nubeero.cia.portal.grant",
                // Only the tenant/exception/audit-config infra sub-packages this flow actually
                // needs — NOT com.nubeero.cia.common.audit (AuditService needs AuditLogRepository,
                // a tenant-schema repository this narrow test doesn't provision) or
                // com.nubeero.cia.common.upload (file-upload validation, unrelated).
                "com.nubeero.cia.common.tenant",
                "com.nubeero.cia.common.exception",
                "com.nubeero.cia.common.config"
        },
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*TestApplication$")
)
@EntityScan(basePackages = "com.nubeero.cia.portal.grant")
@EnableJpaRepositories(basePackages = "com.nubeero.cia.portal.grant")
class PortalAuthTestApplication {
}
