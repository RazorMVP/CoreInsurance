package com.nubeero.cia.compliance.purge;

public record PurgeTenantResult(String schema, boolean ran, int customersPurged, String skippedReason) {
    public static PurgeTenantResult skipped(String schema, String reason) {
        return new PurgeTenantResult(schema, false, 0, reason);
    }
}
