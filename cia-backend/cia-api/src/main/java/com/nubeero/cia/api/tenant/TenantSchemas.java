package com.nubeero.cia.api.tenant;

/** Validation for tenant schema names — a security boundary (names are interpolated into DDL). */
public final class TenantSchemas {
    private TenantSchemas() {}

    private static final java.util.regex.Pattern VALID =
        java.util.regex.Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    /** Throws IllegalArgumentException unless schema is a safe lowercase identifier (Postgres ≤63 chars). */
    public static void validate(String schema) {
        if (schema == null || !VALID.matcher(schema).matches()) {
            throw new IllegalArgumentException("Illegal tenant schema name: " + schema);
        }
    }
}
