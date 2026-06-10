package com.nubeero.cia.api.platform;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when an operation targets a tenant schema that does not exist in {@code public.tenants}.
 *
 * <p>HTTP 404 NOT_FOUND — mirrors {@code ResourceNotFoundException}: all "entity not found"
 * exceptions in this codebase use {@code CiaException(errorCode, message, HttpStatus.NOT_FOUND)}.
 */
public class TenantNotFoundException extends CiaException {

    public TenantNotFoundException(String schema) {
        super("TENANT_NOT_FOUND",
              "Tenant with schema '" + schema + "' does not exist.",
              HttpStatus.NOT_FOUND);
    }
}
