package com.nubeero.cia.api.platform.dto;

import java.time.Instant;

/** Lightweight read-model for a tenant registry row. */
public record TenantSummary(
        String schema,
        String displayName,
        String subdomain,
        boolean active,
        Instant createdAt) {}
