package com.nubeero.cia.partner.webhook;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WebhookDeliveryLogRepository extends JpaRepository<WebhookDeliveryLog, UUID> {
    Page<WebhookDeliveryLog> findAllByWebhookRegistrationId(UUID registrationId, Pageable pageable);
}
