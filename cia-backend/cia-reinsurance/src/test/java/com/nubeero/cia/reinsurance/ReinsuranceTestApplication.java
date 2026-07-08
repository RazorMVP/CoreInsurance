package com.nubeero.cia.reinsurance;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal {@code @SpringBootConfiguration} fixture so {@code @DataJpaTest} can
 * bootstrap a context for {@code cia-reinsurance} in isolation.
 *
 * <p>{@code cia-reinsurance} is a business module with no {@code @SpringBootApplication}
 * of its own (that lives in {@code cia-api}, which is not a dependency of this module —
 * the dependency direction runs the other way). {@code @DataJpaTest} resolves its
 * configuration by walking up from the test class's package looking for a
 * {@code @SpringBootConfiguration}-annotated class; this fixture, colocated in the same
 * package ({@code com.nubeero.cia.reinsurance}) as the IT, is what it finds. Auto-configured
 * JPA / repository / entity scanning defaults to this class's package, which covers every
 * entity and repository in this module (all self-contained — no relationships to entities
 * outside this package) without pulling in unrelated modules on the compile classpath
 * (cia-policy, cia-setup, ...).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class ReinsuranceTestApplication {
}
