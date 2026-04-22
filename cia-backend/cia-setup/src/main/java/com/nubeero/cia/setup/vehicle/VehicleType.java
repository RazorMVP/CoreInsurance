package com.nubeero.cia.setup.vehicle;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "vehicle_types")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleType extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String name;
}
