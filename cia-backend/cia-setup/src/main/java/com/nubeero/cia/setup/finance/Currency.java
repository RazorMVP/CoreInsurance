package com.nubeero.cia.setup.finance;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "currencies")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Currency extends BaseEntity {

    @Column(nullable = false, unique = true, length = 3)
    private String code;

    @Column(nullable = false)
    private String name;

    private String symbol;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;
}
