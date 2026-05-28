package com.nubeero.cia.setup.notification.dto;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;

import java.util.List;

public record NotificationTemplateDefaultsResponse(List<Entry> defaults) {

    public record Entry(
            NotificationTemplateType templateType,
            NotificationChannel channel,
            String subjectTemplate,
            String bodyTemplate) {}
}
