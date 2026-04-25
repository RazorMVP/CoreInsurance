package com.nubeero.cia.reports.domain;

public enum ReportType {
    SYSTEM,   // Pre-built; seeded by Flyway; cannot be deleted
    CUSTOM    // User-created; can be edited and deleted by creator
}
