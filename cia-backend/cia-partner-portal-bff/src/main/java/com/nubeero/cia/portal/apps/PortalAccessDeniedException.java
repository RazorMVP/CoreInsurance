package com.nubeero.cia.portal.apps;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link GrantAuthorizationService} when the current Partner Portal developer has no
 * active grant on a Partner App ({@link GrantAuthorizationService#assertGrant}), or has an active
 * grant but not the {@code MANAGER} role required for the attempted action
 * ({@link GrantAuthorizationService#assertManager}).
 *
 * <p>Maps to HTTP 403 via {@code GlobalExceptionHandler}'s generic {@code CiaException} handler —
 * no bespoke {@code @ExceptionHandler} needed.
 */
public class PortalAccessDeniedException extends CiaException {

    public PortalAccessDeniedException(String message) {
        super("PORTAL_ACCESS_DENIED", message, HttpStatus.FORBIDDEN);
    }
}
