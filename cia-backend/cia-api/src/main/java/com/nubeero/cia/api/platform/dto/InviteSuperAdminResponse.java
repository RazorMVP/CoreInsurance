package com.nubeero.cia.api.platform.dto;

/** Invite result — the temporary password is returned ONCE and never stored. */
public record InviteSuperAdminResponse(String username, String email, String temporaryPassword) {}
