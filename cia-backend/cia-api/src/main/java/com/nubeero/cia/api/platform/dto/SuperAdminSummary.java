package com.nubeero.cia.api.platform.dto;

/** Read shape for a platform super-admin in the list view. */
public record SuperAdminSummary(String username, String email, boolean enabled) {}
