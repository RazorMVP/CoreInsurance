package com.nubeero.cia.notifications;

import com.nubeero.cia.notifications.model.NotificationChannel;
import com.nubeero.cia.notifications.model.NotificationRequest;
import com.nubeero.cia.notifications.model.NotificationResult;

public interface NotificationService {

    NotificationResult send(NotificationRequest request);

    default boolean supports(NotificationChannel channel) {
        return true;
    }
}
