package com.nubeero.cia.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component("externalDependencies")
public class ExternalDependenciesHealthIndicator implements HealthIndicator {

    private static final List<String> PRODUCTION_LIKE = List.of(
            "prod", "production", "staging", "stage", "uat", "preprod", "pre-production"
    );

    private final Environment environment;

    public ExternalDependenciesHealthIndicator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up()
                .withDetail("keycloakIssuer", configured("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                .withDetail("storage", configured("cia.storage.endpoint"))
                .withDetail("kycProvider", environment.getProperty("cia.kyc.provider", ""))
                .withDetail("naicomMode", environment.getProperty("cia.naicom.mode", ""))
                .withDetail("niidMode", environment.getProperty("cia.niid.mode", ""));

        if (!isProductionLike()) {
            return builder.withDetail("mode", "dev-test-relaxed").build();
        }

        boolean ready = isConfigured("spring.security.oauth2.resourceserver.jwt.issuer-uri")
                && isConfigured("cia.storage.endpoint")
                && isConfigured("cia.kyc.provider-url")
                && isLiveMode("cia.naicom.mode", "cia.naicom.api-url")
                && isLiveMode("cia.niid.mode", "cia.niid.api-url");

        if (ready) {
            return builder.withDetail("mode", "production-required").build();
        }

        return Health.down()
                .withDetails(builder.build().getDetails())
                .withDetail("mode", "production-required")
                .withDetail("reason", "Required external dependency configuration is incomplete")
                .build();
    }

    private boolean isProductionLike() {
        String ciaEnvironment = normalize(environment.getProperty("cia.environment"));
        if (PRODUCTION_LIKE.contains(ciaEnvironment)) {
            return true;
        }
        for (String profile : environment.getActiveProfiles()) {
            if (PRODUCTION_LIKE.contains(normalize(profile))) {
                return true;
            }
        }
        return false;
    }

    private boolean isLiveMode(String modeProperty, String urlProperty) {
        return "live".equals(normalize(environment.getProperty(modeProperty)))
                && isConfigured(urlProperty);
    }

    private boolean isConfigured(String propertyName) {
        String value = environment.getProperty(propertyName);
        return value != null && !value.isBlank();
    }

    private String configured(String propertyName) {
        return isConfigured(propertyName) ? "configured" : "missing";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
