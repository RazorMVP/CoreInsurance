package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "sbus")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Sbu extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String code;
}
