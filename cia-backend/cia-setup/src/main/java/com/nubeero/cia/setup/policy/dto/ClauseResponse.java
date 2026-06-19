package com.nubeero.cia.setup.policy.dto;

import com.nubeero.cia.setup.policy.ClauseApplicability;
import com.nubeero.cia.setup.policy.ClauseType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ClauseResponse {
    private UUID id;
    private String title;
    private String text;
    private ClauseType type;
    private ClauseApplicability applicability;
    private List<String> productIds;
    private Instant createdAt;
    private Instant updatedAt;
}
