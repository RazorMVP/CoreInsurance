package com.nubeero.cia.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fail-fast guardrails that prevent local/dev defaults from being used in
 * production-like runtime profiles.
 */
public class ProductionSafetyValidator implements EnvironmentPostProcessor {

    private static final Set<String> DEV_OR_TEST_PROFILES = Set.of("dev", "test");
    private static final Set<String> PRODUCTION_LIKE_NAMES = Set.of(
            "prod", "production", "staging", "stage", "uat", "preprod", "pre-production"
    );
    private static final Set<String> SUPPORTED_PROD_STORAGE_TYPES = Set.of("minio", "s3");
    private static final Set<String> SUPPORTED_PROD_KYC_PROVIDERS = Set.of("dojah", "prembly");

    private static final String DEV_PII_KEY = "dev-pii-key-do-not-use-in-prod-CHANGE-ME";
    private static final String DEV_WEBHOOK_SECRET = "dev-secret-replace-in-prod";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Set<String> activeProfiles = activeProfiles(environment);

        if (activeProfiles.isEmpty()) {
            throw new IllegalStateException(
                    "No Spring profile is active. Set SPRING_PROFILES_ACTIVE explicitly. " +
                    "Use 'dev' for local development, 'test' for automated tests, or a " +
                    "deployment profile such as 'staging' or 'prod'."
            );
        }

        boolean productionLike = isProductionLike(environment, activeProfiles);
        if (productionLike && activeProfiles.stream().anyMatch(DEV_OR_TEST_PROFILES::contains)) {
            throw new IllegalStateException(
                    "Production-like environment cannot run with dev/test Spring profiles. " +
                    "Remove dev/test from SPRING_PROFILES_ACTIVE before deploying."
            );
        }

        boolean devOrTestOnly = activeProfiles.stream().allMatch(DEV_OR_TEST_PROFILES::contains);
        if (devOrTestOnly) {
            return;
        }

        validateProtectedEnvironment(environment);
    }

    private Set<String> activeProfiles(ConfigurableEnvironment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
    }

    private boolean isProductionLike(ConfigurableEnvironment environment, Set<String> activeProfiles) {
        String ciaEnvironment = normalize(environment.getProperty("cia.environment"));
        return PRODUCTION_LIKE_NAMES.contains(ciaEnvironment)
                || activeProfiles.stream().anyMatch(PRODUCTION_LIKE_NAMES::contains);
    }

    private void validateProtectedEnvironment(ConfigurableEnvironment environment) {
        requireExternalValue(environment, "spring.datasource.url");
        requireNonBlank(environment, "spring.datasource.username");
        requireNonDefault(environment, "spring.datasource.password", "cia_dev");

        requireExternalValue(environment, "spring.security.oauth2.resourceserver.jwt.issuer-uri");

        String storageType = requireNonBlank(environment, "cia.storage.type");
        if (!SUPPORTED_PROD_STORAGE_TYPES.contains(normalize(storageType))) {
            throw new IllegalStateException(
                    "Property 'cia.storage.type' must be one of " + SUPPORTED_PROD_STORAGE_TYPES +
                    " outside dev/test profiles."
            );
        }
        requireNonBlank(environment, "cia.storage.bucket-name");
        requireExternalValue(environment, "cia.storage.endpoint");
        requireNonDefault(environment, "cia.storage.access-key", "minioadmin");
        requireNonDefault(environment, "cia.storage.secret-key", "minioadmin");

        requireNonDefault(environment, "cia.security.pii-key", DEV_PII_KEY);
        requireNonDefault(environment, "cia.partner.webhook.signing-secret", DEV_WEBHOOK_SECRET);
        requireMinimumLength(environment, "cia.partner.webhook.signing-secret", 32);

        String kycProvider = requireNonBlank(environment, "cia.kyc.provider");
        String normalizedKycProvider = normalize(kycProvider);
        if (!SUPPORTED_PROD_KYC_PROVIDERS.contains(normalizedKycProvider)) {
            throw new IllegalStateException(
                    "Property 'cia.kyc.provider' must be one of " + SUPPORTED_PROD_KYC_PROVIDERS +
                    " outside dev/test profiles. Mock or unimplemented KYC providers are not allowed."
            );
        }
        requireExternalValue(environment, "cia.kyc.provider-url");

        requireMode(environment, "cia.naicom.mode", "live");
        requireExternalValue(environment, "cia.naicom.api-url");
        requireMode(environment, "cia.niid.mode", "live");
        requireExternalValue(environment, "cia.niid.api-url");
    }

    private void requireMode(ConfigurableEnvironment environment, String propertyName, String requiredValue) {
        String actual = requireNonBlank(environment, propertyName);
        if (!requiredValue.equals(normalize(actual))) {
            throw new IllegalStateException(
                    "Property '" + propertyName + "' must be '" + requiredValue +
                    "' outside dev/test profiles."
            );
        }
    }

    private void requireExternalValue(ConfigurableEnvironment environment, String propertyName) {
        String value = requireNonBlank(environment, propertyName);
        if (isLocalValue(value)) {
            throw new IllegalStateException(
                    "Property '" + propertyName + "' uses a local development value. " +
                    "Set a non-local production value outside dev/test profiles."
            );
        }
    }

    private void requireNonDefault(ConfigurableEnvironment environment, String propertyName, String disallowedValue) {
        String value = requireNonBlank(environment, propertyName);
        if (normalize(value).equals(normalize(disallowedValue))) {
            throw new IllegalStateException(
                    "Property '" + propertyName + "' uses a checked-in development default. " +
                    "Set it from a secret manager or deployment environment outside dev/test profiles."
            );
        }
    }

    private void requireMinimumLength(ConfigurableEnvironment environment, String propertyName, int minimumLength) {
        String value = requireNonBlank(environment, propertyName);
        if (value.length() < minimumLength) {
            throw new IllegalStateException(
                    "Property '" + propertyName + "' must be at least " + minimumLength +
                    " characters outside dev/test profiles."
            );
        }
    }

    private String requireNonBlank(ConfigurableEnvironment environment, String propertyName) {
        String value = environment.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required property '" + propertyName + "' is not set outside dev/test profiles."
            );
        }
        return value;
    }

    private boolean isLocalValue(String value) {
        String lower = normalize(value);
        return lower.contains("localhost")
                || lower.contains("127.0.0.1")
                || lower.contains("0.0.0.0")
                || lower.contains("[::1]")
                || lower.contains("://::1");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
