package com.nubeero.cia.setup.notification.dto;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record NotificationTemplateRequest(
        @NotNull NotificationTemplateType templateType,
        @NotNull NotificationChannel channel,
        @Size(max = 500) String subjectTemplate,
        @Size(max = 1000) String bodyTemplate) {}
