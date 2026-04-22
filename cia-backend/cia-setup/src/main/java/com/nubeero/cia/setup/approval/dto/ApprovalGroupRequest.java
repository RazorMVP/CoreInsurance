package com.nubeero.cia.setup.approval.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ApprovalGroupRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String entityType;

    @NotEmpty
    @Valid
    private List<ApprovalLevelRequest> levels;

    @Data
    public static class ApprovalLevelRequest {
        @Min(1)
        private int levelOrder;

        @NotBlank
        private String approverUserId;

        private String approverName;

        @DecimalMin("0.0")
        private BigDecimal maxAmount;
    }
}
