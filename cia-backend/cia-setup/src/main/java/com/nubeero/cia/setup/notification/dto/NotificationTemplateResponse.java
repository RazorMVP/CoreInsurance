package com.nubeero.cia.setup.notification.dto;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.setup.notification.TenantNotificationTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * NOTE: BaseEntity exposes createdBy but NOT updatedBy (no @LastModifiedBy field).
 * updatedBy is therefore absent from this response.
 */
public record NotificationTemplateResponse(
        UUID id,
        NotificationTemplateType templateType,
        NotificationChannel channel,
        String subjectTemplate,
        String bodyTemplate,
        Instant createdAt,
        Instant updatedAt,
        String createdBy) {

    public static NotificationTemplateResponse from(TenantNotificationTemplate e) {
        return new NotificationTemplateResponse(
                e.getId(),
                e.getTemplateType(),
                e.getChannel(),
                e.getSubjectTemplate(),
                e.getBodyTemplate(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getCreatedBy());
    }
}
