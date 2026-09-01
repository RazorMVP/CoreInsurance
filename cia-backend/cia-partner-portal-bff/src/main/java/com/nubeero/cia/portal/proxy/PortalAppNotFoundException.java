package com.nubeero.cia.portal.proxy;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a grant resolves ({@code GrantAuthorizationService.assertGrant}/{@code
 * assertManager} succeeded) but the Partner App it points at can't be found (or is soft-deleted)
 * in its own tenant schema — a data-integrity edge case, not an authorization failure, so it maps
 * to 404 rather than the 403 {@code PortalAccessDeniedException} uses.
 */
public class PortalAppNotFoundException extends CiaException {

    public PortalAppNotFoundException(String message) {
        super("PORTAL_APP_NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }
}
