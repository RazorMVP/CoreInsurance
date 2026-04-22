package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "insurance_companies")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsuranceCompany extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "rc_number")
    private String rcNumber;

    @Column(name = "naicom_license")
    private String naicomLicense;

    private String address;
    private String email;
    private String phone;
}
