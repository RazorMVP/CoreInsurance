package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * NAICOM-licensed loss adjuster (firm or individual). Used by claims for loss
 * assessment — distinct from surveyors (who do pre-loss inspections). Internal
 * adjusters are staff; external adjusters are independent firms invoiced via
 * cia-finance.
 */
@Entity
@Table(name = "adjusters")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Adjuster extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdjusterType type;

    @Column(name = "license_number")
    private String licenseNumber;

    private String email;
    private String phone;
    private String address;
}
