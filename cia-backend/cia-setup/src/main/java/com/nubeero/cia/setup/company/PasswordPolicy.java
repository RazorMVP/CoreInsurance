package com.nubeero.cia.setup.company;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "password_policies")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordPolicy extends BaseEntity {

    @Column(name = "min_length", nullable = false)
    private int minLength;

    @Column(name = "max_length", nullable = false)
    private int maxLength;

    @Column(name = "require_uppercase", nullable = false)
    private boolean requireUppercase;

    @Column(name = "require_lowercase", nullable = false)
    private boolean requireLowercase;

    @Column(name = "require_numbers", nullable = false)
    private boolean requireNumbers;

    @Column(name = "require_special", nullable = false)
    private boolean requireSpecial;

    @Column(name = "expiry_days", nullable = false)
    private int expiryDays;

    @Column(name = "max_failed_attempts", nullable = false)
    private int maxFailedAttempts;
}
