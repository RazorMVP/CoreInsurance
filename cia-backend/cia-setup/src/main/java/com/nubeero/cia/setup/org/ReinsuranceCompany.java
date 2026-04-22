package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "reinsurance_companies")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReinsuranceCompany extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "rc_number")
    private String rcNumber;

    private String address;
    private String email;
    private String phone;

    @Column(nullable = false)
    private String country;
}
