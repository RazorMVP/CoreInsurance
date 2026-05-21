package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * NAICOM-licensed insurance agent (individual or corporate). Agents represent
 * the INSURER and earn commission on policies sold, distinct from Brokers
 * (who represent the INSURED) and from RelationshipManagers (who are internal
 * staff, not commission-earning external counterparties).
 */
@Entity
@Table(name = "agents")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Agent extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentType type;

    @Column(name = "license_number")
    private String licenseNumber;

    private String email;
    private String phone;
    private String address;
}
