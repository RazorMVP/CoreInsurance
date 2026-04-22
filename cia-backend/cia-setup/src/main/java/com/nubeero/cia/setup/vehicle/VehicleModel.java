package com.nubeero.cia.setup.vehicle;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "vehicle_models")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleModel extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "make_id", nullable = false)
    private VehicleMake make;

    @Column(nullable = false)
    private String name;
}
