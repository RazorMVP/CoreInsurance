package com.nubeero.cia.setup.company;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "company_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanySettings extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "rc_number")
    private String rcNumber;

    @Column(name = "naicom_license_number")
    private String naicomLicenseNumber;

    private String address;
    private String city;
    private String state;
    private String email;
    private String phone;

    @Column(name = "logo_path")
    private String logoPath;

    private String website;
}
