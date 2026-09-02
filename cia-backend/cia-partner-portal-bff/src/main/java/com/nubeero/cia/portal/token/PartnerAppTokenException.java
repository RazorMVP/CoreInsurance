package com.nubeero.cia.portal.token;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a partner-app access token cannot be minted — the Keycloak
 * admin client is unavailable, the app's client is missing from its tenant
 * realm, or the client-credentials grant against the tenant realm's token
 * endpoint fails. Never carries the {@code client_secret} in its message.
 */
public class PartnerAppTokenException extends CiaException {

    public PartnerAppTokenException(String errorCode, String message) {
        super(errorCode, message, HttpStatus.BAD_GATEWAY);
    }

    public PartnerAppTokenException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, HttpStatus.BAD_GATEWAY, cause);
    }
}
