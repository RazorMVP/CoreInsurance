package com.nubeero.cia.setup.company.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class PasswordPolicyResponse {
    private UUID id;
    private int  minLength;
    private int  maxLength;
    private boolean requireUppercase;
    private boolean requireLowercase;
    private boolean requireNumbers;
    private boolean requireSpecial;
    private int  expiryDays;
    private int  maxFailedAttempts;
    private Instant createdAt;
    private Instant updatedAt;
}
