package com.nubeero.cia.partner.config;

import com.giffing.bucket4j.spring.boot.starter.context.properties.Bucket4JBootProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Rate limiting for partner API endpoints is configured via application.yml
 * under bucket4j.filters. Each partner app (client_id from JWT) gets
 * 1000 requests/minute by default, configurable per-plan via Bucket4j
 * Redis-backed token buckets.
 *
 * See application.yml: bucket4j.filters[0].url=/partner/v1/.*
 */
@Configuration
public class RateLimitConfig {
    // Rate limit tuning is done in application.yml — no Java config needed here.
}
