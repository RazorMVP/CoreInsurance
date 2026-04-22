package com.nubeero.cia.setup.access;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "access_groups")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessGroup extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    @OneToMany(mappedBy = "accessGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<AccessGroupPermission> permissions = new ArrayList<>();
}
