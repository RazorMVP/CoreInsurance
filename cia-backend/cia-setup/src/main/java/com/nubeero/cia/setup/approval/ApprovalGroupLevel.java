package com.nubeero.cia.setup.approval;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "approval_group_levels")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalGroupLevel extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approval_group_id", nullable = false)
    private ApprovalGroup approvalGroup;

    @Column(name = "level_order", nullable = false)
    private int levelOrder;

    @Column(name = "approver_user_id", nullable = false)
    private String approverUserId;

    @Column(name = "approver_name")
    private String approverName;

    @Column(name = "max_amount", precision = 18, scale = 2)
    private BigDecimal maxAmount;
}
