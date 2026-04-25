package com.nubeero.cia.dashboard;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RecentActivityDto {
    String id;
    String entityType;   // "Policy", "Claim", "Quote", etc.
    String entityId;     // reference number e.g. "POL-2026-00041"
    String action;       // "APPROVE", "CREATE", "UPDATE", etc.
    String userName;
    String timeAgo;      // "2m ago", "1h ago", etc.
    String statusGroup;  // "active" | "pending" | "rejected" for badge colour
}
