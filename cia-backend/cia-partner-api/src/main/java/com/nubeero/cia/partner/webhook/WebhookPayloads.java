package com.nubeero.cia.partner.webhook;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds webhook payload maps that tolerate {@code null} values.
 *
 * <p>{@link Map#of(Object...)} throws {@link NullPointerException} on any null
 * key or value. Webhook payloads are JSON envelopes assembled from business
 * event fields, several of which are legitimately nullable (e.g. an
 * endorsement with no broker, a claim with no approved amount yet, or a null
 * tenant id when an event is published off the request thread). A null field
 * must serialise as JSON {@code null}, not abort the whole fan-out with an NPE
 * that gets swallowed and silently drops the event. See cia-log 2026-06-28.
 */
final class WebhookPayloads {

    private WebhookPayloads() {}

    /**
     * Like {@code Map.of(k1, v1, k2, v2, ...)} but null-tolerant and
     * insertion-ordered. Keys must be non-null and unique; values may be null.
     */
    static Map<String, Object> of(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected an even number of key/value args");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
