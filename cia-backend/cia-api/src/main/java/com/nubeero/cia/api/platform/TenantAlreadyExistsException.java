package com.nubeero.cia.api.platform;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a tenant with the given schema name or subdomain already exists in
 * {@code public.tenants}.
 *
 * <p>HTTP 409 CONFLICT — mirrors {@code ResourceInUseException} and
 * {@code FiscalYearNameConflictException}: all uniqueness-violation-at-creation
 * exceptions in this codebase use {@code CiaException(errorCode, message, HttpStatus.CONFLICT)}.
 */
public class TenantAlreadyExistsException extends CiaException {

    public TenantAlreadyExistsException(String schema, String subdomain) {
        super("TENANT_ALREADY_EXISTS",
              "Tenant with schema '" + schema + "' or subdomain '" + subdomain + "' already exists.",
              HttpStatus.CONFLICT);
    }
}
