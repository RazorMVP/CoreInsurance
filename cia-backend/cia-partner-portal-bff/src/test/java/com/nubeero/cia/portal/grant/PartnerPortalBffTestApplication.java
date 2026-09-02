package com.nubeero.cia.portal.grant;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal {@code @SpringBootConfiguration} fixture so {@code @DataJpaTest} can
 * bootstrap a context for {@code cia-partner-portal-bff} in isolation.
 *
 * <p>{@code cia-partner-portal-bff} is a module with no {@code @SpringBootApplication}
 * of its own (that lives in {@code cia-api}, which is not a dependency of this module —
 * the dependency direction runs the other way). {@code @DataJpaTest} resolves its
 * configuration by walking up from the test class's package looking for a
 * {@code @SpringBootConfiguration}-annotated class; this fixture, colocated in the same
 * package ({@code com.nubeero.cia.portal.grant}) as the IT, is what it finds.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class PartnerPortalBffTestApplication {
}
