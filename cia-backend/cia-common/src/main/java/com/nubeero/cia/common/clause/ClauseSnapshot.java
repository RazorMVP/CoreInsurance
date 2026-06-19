package com.nubeero.cia.common.clause;

/**
 * Point-in-time snapshot of a selected policy clause, frozen onto a quote or policy at selection
 * time and stored in the {@code selected_clauses} JSONB column. Denormalized on purpose (matching
 * the codebase's snapshot pattern) so an issued quote/policy document keeps the exact clause text
 * it was created with, regardless of later edits to the clause master.
 */
public record ClauseSnapshot(String id, String title, String text, String type) {}
