package com.nubeero.cia.setup.approval;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "approval_groups")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalGroup extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @OneToMany(mappedBy = "approvalGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("levelOrder ASC")
    @Builder.Default
    private List<ApprovalGroupLevel> levels = new ArrayList<>();
}
