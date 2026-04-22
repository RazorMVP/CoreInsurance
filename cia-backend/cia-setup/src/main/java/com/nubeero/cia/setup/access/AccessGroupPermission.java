package com.nubeero.cia.setup.access;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "access_group_permissions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessGroupPermission extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "access_group_id", nullable = false)
    private AccessGroup accessGroup;

    @Column(nullable = false, length = 100)
    private String permission;
}
