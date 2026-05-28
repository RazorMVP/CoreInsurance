package com.nubeero.cia.setup.notification.dto;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record NotificationTemplatePreviewRequest(
        @NotNull NotificationTemplateType templateType,
        @NotNull NotificationChannel channel,
        String subjectTemplate,
        String bodyTemplate,
        @NotNull Map<String, Object> sampleValues) {}
