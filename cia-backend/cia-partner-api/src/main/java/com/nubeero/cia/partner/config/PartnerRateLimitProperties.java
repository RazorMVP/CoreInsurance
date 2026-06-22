package com.nubeero.cia.partner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the per-client partner API rate limiter ({@code cia.partner.rate-limit.*}).
 *
 * <p>The per-partner request budget itself lives on {@code PartnerApp.rateLimitRpm}
 * (set at provisioning per plan tier) — these properties only cover the limiter's
 * global behaviour: whether it is enabled and the fallback budget for a client_id
 * with no resolvable {@code PartnerApp} row.
 */
@ConfigurationProperties(prefix = "cia.partner.rate-limit")
public class PartnerRateLimitProperties {

    /** Master switch. When false the filter passes every request through. */
    private boolean enabled = true;

    /**
     * Requests/minute applied when a client_id cannot be resolved to a
     * {@code PartnerApp} (e.g. a freshly-issued token before the row is visible,
     * or an inactive app). Conservative by design.
     */
    private int defaultRpm = 60;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getDefaultRpm() { return defaultRpm; }
    public void setDefaultRpm(int defaultRpm) { this.defaultRpm = defaultRpm; }
}
