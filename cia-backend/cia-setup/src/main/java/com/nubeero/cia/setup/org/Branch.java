package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "branches")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Branch extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sbu_id")
    private Sbu sbu;

    private String address;
}
