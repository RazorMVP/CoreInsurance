package com.nubeero.cia.setup.approval.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class ApprovalGroupResponse {
    private UUID id;
    private String name;
    private String entityType;
    private List<ApprovalLevelResponse> levels;
    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    public static class ApprovalLevelResponse {
        private UUID id;
        private int levelOrder;
        private String approverUserId;
        private String approverName;
        private BigDecimal maxAmount;
    }
}
