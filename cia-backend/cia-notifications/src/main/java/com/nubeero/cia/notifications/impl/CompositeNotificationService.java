package com.nubeero.cia.notifications.impl;

import com.nubeero.cia.notifications.NotificationService;
import com.nubeero.cia.notifications.model.NotificationRequest;
import com.nubeero.cia.notifications.model.NotificationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class CompositeNotificationService implements NotificationService {

    private final List<NotificationService> delegates;

    @Override
    public NotificationResult send(NotificationRequest request) {
        return delegates.stream()
                .filter(svc -> !(svc instanceof CompositeNotificationService))
                .filter(svc -> svc.supports(request.getChannel()))
                .findFirst()
                .map(svc -> svc.send(request))
                .orElseGet(() -> {
                    log.warn("No NotificationService found for channel={}", request.getChannel());
                    return NotificationResult.builder()
                            .success(false)
                            .errorMessage("No handler for channel: " + request.getChannel())
                            .build();
                });
    }
}
