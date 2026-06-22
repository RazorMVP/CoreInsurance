package com.nubeero.cia.partner.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Partner API rate limiting.
 *
 * <p>Implemented by {@link PartnerRateLimitFilter} + {@link PartnerRateLimitService}:
 * each client_id (from the validated JWT) gets its own token bucket sized to that
 * partner's {@code PartnerApp.rateLimitRpm}, so partners are isolated and tiered.
 * This replaced the old declarative {@code bucket4j} starter filter, which used a
 * single shared {@code /partner/v1/.*} bucket keyed by nothing — one abusive
 * partner could exhaust the budget for everyone. The starter is now disabled
 * (see {@code application.yml: bucket4j.enabled=false}).
 *
 * <p>Tuning lives in {@link PartnerRateLimitProperties} ({@code cia.partner.rate-limit.*}).
 */
@Configuration
@EnableConfigurationProperties(PartnerRateLimitProperties.class)
public class RateLimitConfig {
    // Beans are component-scanned; this class only enables the properties.
}
