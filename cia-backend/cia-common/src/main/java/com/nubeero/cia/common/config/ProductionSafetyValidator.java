package com.nubeero.cia.common.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Fail-fast guard against shipping development defaults into a hardened
 * (production / staging) deployment.
 *
 * <h2>Why a dedicated marker instead of the Spring profile</h2>
 * The risk being defended against is precisely that a production deploy
 * <em>forgets</em> to set {@code SPRING_PROFILES_ACTIVE} — in which case the
 * active profile defaults to {@code dev}, and {@code DevSecurityConfig}
 * (profile {@code dev}) permits every request with no JWT validation, shipping
 * an open API. You therefore cannot use the profile itself to detect "this is
 * production". Instead this validator keys off an explicit, prod-only marker:
 * <pre>cia.deployment.environment (env CIA_DEPLOYMENT_ENVIRONMENT)</pre>
 * which defaults to {@code local}. Local dev and the entire Testcontainers IT
 * suite never set it, so this validator is a no-op there. Operations set it to
 * {@code production} (or {@code staging}) in the deployment manifest — one
 * required env var that then enforces all the others.
 *
 * <h2>What it enforces in a hardened environment</h2>
 * <ol>
 *   <li><b>The {@code dev} Spring profile must not be active</b> — otherwise
 *       {@code DevSecurityConfig} bypasses authentication. (Closes the
 *       "forgot SPRING_PROFILES_ACTIVE → open API" P1.)</li>
 *   <li><b>No secret may still hold its known-weak development default</b> —
 *       the NDPR PII encryption key, the webhook HMAC signing secret, the
 *       object-storage credentials, and the database password. Each known-bad
 *       default that survives into a hardened env is a silent compromise (e.g.
 *       PII encrypted under a value that is published in this repo).</li>
 * </ol>
 * All violations are collected and reported in a single exception so an
 * operator fixes them in one pass rather than one redeploy at a time.
 *
 * <p>Runs as an {@link EnvironmentPostProcessor} (after placeholder resolution,
 * before any bean is created), registered via
 * {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports}.
 * Complements {@link PiiKeyValidator}, which independently enforces the PII
 * key's charset/length in <em>every</em> environment (SQL-injection guard);
 * this validator adds the "not the weak default" dimension, but only when
 * hardened.
 */
public class ProductionSafetyValidator implements EnvironmentPostProcessor {

    static final String ENV_MARKER = "cia.deployment.environment";

    /** Marker values that demand production-grade configuration. */
    private static final Set<String> HARDENED = Set.of("production", "prod", "staging");

    /**
     * Known-weak development defaults, keyed by the property that must not
     * still hold them in a hardened environment. Values mirror the
     * {@code ${ENV:default}} fallbacks in {@code cia-api/application.yml}.
     */
    private record WeakDefault(String property, String devValue, String envVar) {}

    private static final List<WeakDefault> WEAK_DEFAULTS = List.of(
        new WeakDefault("cia.security.pii-key",
            "dev-pii-key-do-not-use-in-prod-CHANGE-ME", "PII_ENCRYPTION_KEY"),
        new WeakDefault("cia.partner.webhook.signing-secret",
            "dev-secret-replace-in-prod", "WEBHOOK_SIGNING_SECRET"),
        new WeakDefault("cia.storage.access-key", "minioadmin", "STORAGE_ACCESS_KEY"),
        new WeakDefault("cia.storage.secret-key", "minioadmin", "STORAGE_SECRET_KEY"),
        new WeakDefault("spring.datasource.password", "cia_dev", "DB_PASSWORD")
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String marker = environment.getProperty(ENV_MARKER, "local").trim().toLowerCase();
        if (!HARDENED.contains(marker)) {
            return; // local / test / unset — nothing to enforce
        }

        List<String> violations = new ArrayList<>();

        // (1) the dev profile must not be active in a hardened environment
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.contains("dev")) {
            violations.add(
                "- Spring profile 'dev' is active (DevSecurityConfig permits ALL requests "
                + "with no authentication). Set SPRING_PROFILES_ACTIVE to a non-dev profile.");
        }

        // (2) no secret may still hold its known-weak development default
        for (WeakDefault wd : WEAK_DEFAULTS) {
            String current = environment.getProperty(wd.property());
            if (wd.devValue().equals(current)) {
                violations.add(
                    "- '" + wd.property() + "' still holds its development default. "
                    + "Set environment variable " + wd.envVar() + " to a real secret.");
            }
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                "Refusing to start: " + ENV_MARKER + "=" + marker
                + " (hardened) but development defaults / settings remain:\n"
                + String.join("\n", violations)
                + "\n\nThis guard exists because a production deploy that ships these "
                + "values is silently insecure (open API, PII under a published key, "
                + "forgeable webhooks). Fix every line above, or set "
                + ENV_MARKER + "=local for non-production environments.");
        }
    }
}
