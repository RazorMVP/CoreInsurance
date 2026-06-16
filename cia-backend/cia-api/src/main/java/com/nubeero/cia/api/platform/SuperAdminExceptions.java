package com.nubeero.cia.api.platform;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

/** Platform super-admin lifecycle exceptions. */
public final class SuperAdminExceptions {
    private SuperAdminExceptions() {}

    /** 409 — username already exists in the platform realm. */
    public static class AlreadyExists extends CiaException {
        public AlreadyExists(String username) {
            super("SUPER_ADMIN_ALREADY_EXISTS",
                  "A super-admin named '" + username + "' already exists.", HttpStatus.CONFLICT);
        }
    }

    /** 404 — no such super-admin. */
    public static class NotFound extends CiaException {
        public NotFound(String username) {
            super("SUPER_ADMIN_NOT_FOUND",
                  "No super-admin named '" + username + "'.", HttpStatus.NOT_FOUND);
        }
    }

    /** 409 — a super-admin may not revoke their own access (self-lockout guard). */
    public static class CannotRevokeSelf extends CiaException {
        public CannotRevokeSelf() {
            super("CANNOT_REVOKE_SELF",
                  "You cannot revoke your own super-admin access.", HttpStatus.CONFLICT);
        }
    }

    /** 409 — refusing to remove the last remaining super-admin (zero-super-admin guard). */
    public static class CannotRevokeLast extends CiaException {
        public CannotRevokeLast() {
            super("CANNOT_REVOKE_LAST_SUPER_ADMIN",
                  "Cannot revoke the last remaining super-admin.", HttpStatus.CONFLICT);
        }
    }

    /** 503 — the Keycloak admin client is disabled (dev without Keycloak). Not a CiaException
     *  (mirrors UserController's controller-local 503 path). */
    public static class KeycloakAdminDisabled extends RuntimeException {
        public KeycloakAdminDisabled() {
            super("Keycloak admin client is disabled — super-admin management is unavailable.");
        }
    }
}
