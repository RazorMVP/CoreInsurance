package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "relationship_managers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationshipManager extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String email;
    private String phone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;
}
