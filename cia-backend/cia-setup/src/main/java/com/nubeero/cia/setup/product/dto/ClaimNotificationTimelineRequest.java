package com.nubeero.cia.setup.product.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class ClaimNotificationTimelineRequest {

    @Min(1)
    private int notificationDays;
}
