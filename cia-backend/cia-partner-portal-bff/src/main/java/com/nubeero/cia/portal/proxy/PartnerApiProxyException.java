package com.nubeero.cia.portal.proxy;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when {@link PartnerApiProxyClient} cannot reach {@code /partner/v1/**} at all — connection
 * refused, DNS failure, or a connect/read timeout. Distinct from a normal upstream response (even
 * an upstream 4xx/5xx), which is relayed verbatim rather than thrown as an exception.
 */
public class PartnerApiProxyException extends CiaException {

    public PartnerApiProxyException(String message, Throwable cause) {
        super("PARTNER_API_PROXY_UNAVAILABLE", message, HttpStatus.BAD_GATEWAY, cause);
    }
}
