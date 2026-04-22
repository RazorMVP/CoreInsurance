package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "surveyors")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Surveyor extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SurveyorType type;

    @Column(name = "license_number")
    private String licenseNumber;

    private String email;
    private String phone;
}
