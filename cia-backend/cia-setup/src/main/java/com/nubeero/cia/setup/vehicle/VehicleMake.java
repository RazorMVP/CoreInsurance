package com.nubeero.cia.setup.vehicle;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "vehicle_makes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleMake extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String name;

    @OneToMany(mappedBy = "make", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<VehicleModel> models = new ArrayList<>();
}
