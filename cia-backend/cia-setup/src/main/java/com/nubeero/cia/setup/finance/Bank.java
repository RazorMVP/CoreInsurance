package com.nubeero.cia.setup.finance;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "banks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Bank extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String code;
}
