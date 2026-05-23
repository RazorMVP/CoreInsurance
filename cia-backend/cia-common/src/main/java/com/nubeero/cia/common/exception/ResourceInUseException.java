package com.nubeero.cia.common.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Thrown when a master-data resource cannot be soft-deleted because other
 * active (non-soft-deleted) records reference it via foreign key.
 *
 * <p>HTTP 409 CONFLICT — the request itself is well-formed (so 4xx, not 5xx),
 * but the current state of the system prevents the operation (so CONFLICT,
 * not UNPROCESSABLE_ENTITY which is for invalid request bodies).
 *
 * <p>PostgreSQL's FK constraints don't honour {@code deleted_at IS NULL} —
 * a foreign key pointing to a soft-deleted row is still valid at the DB
 * level. The "active references exist" semantic is purely application-side,
 * so this check must happen in service code before the soft-delete write.
 */
public class ResourceInUseException extends CiaException {

    public ResourceInUseException(String resourceType, UUID id, String referencedBy, long count) {
        super(
                "RESOURCE_IN_USE",
                String.format("Cannot delete %s %s: %d active %s(s) reference it",
                        resourceType, id, count, referencedBy),
                HttpStatus.CONFLICT);
    }
}
