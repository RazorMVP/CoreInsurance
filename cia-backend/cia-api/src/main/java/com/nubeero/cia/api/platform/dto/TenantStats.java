package com.nubeero.cia.api.platform.dto;

/** Dashboard counters: total tenants, active, and suspended (= total − active). */
public record TenantStats(long total, long active, long suspended) {}
