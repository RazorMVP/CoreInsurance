package com.nubeero.cia.setup.notification.dto;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;

import java.util.List;
import java.util.Set;

public record NotificationTemplateVariablesResponse(List<Entry> variables) {

    public record Entry(
            NotificationTemplateType templateType,
            NotificationChannel channel,
            Set<String> allowedVariables) {}
}
