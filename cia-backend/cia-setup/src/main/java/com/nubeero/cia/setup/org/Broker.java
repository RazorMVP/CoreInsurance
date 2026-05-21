package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "brokers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Broker extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(name = "rc_number")
    private String rcNumber;

    /** NAICOM broker licence number (V49). Optional for existing pre-V49 rows;
     *  new brokers are expected to supply it. Mirrors the same field on
     *  surveyors, adjusters, agents, and insurance_companies. */
    @Column(name = "license_number", length = 50)
    private String licenseNumber;

    private String address;
    private String email;
    private String phone;
}
