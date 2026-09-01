package com.nubeero.cia.portal.proxy;

import org.springframework.http.HttpHeaders;

/**
 * The raw upstream {@code /partner/v1/**} response {@link PartnerApiProxyClient} hands back —
 * status, headers, and body bytes, all relayed VERBATIM by {@code PortalProxyController}. Never
 * interpreted or unwrapped: a scope-denied 403 from {@code PartnerScopeFilter}, or a 429 from the
 * rate limiter, must reach the developer byte-for-byte, exactly as it would for a genuine external
 * integration.
 */
public record ProxyResult(int status, HttpHeaders headers, byte[] body) {
}
