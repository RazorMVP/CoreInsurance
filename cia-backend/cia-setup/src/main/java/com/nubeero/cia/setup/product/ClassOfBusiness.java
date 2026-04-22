package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "classes_of_business")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassOfBusiness extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    private String description;
}
