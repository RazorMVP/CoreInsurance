package com.nubeero.cia.partner.webhook;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookRegistrationRepository extends JpaRepository<WebhookRegistration, UUID> {

    Page<WebhookRegistration> findAllByPartnerAppIdAndDeletedAtIsNull(UUID partnerAppId, Pageable pageable);

    Optional<WebhookRegistration> findByIdAndDeletedAtIsNull(UUID id);

    List<WebhookRegistration> findAllByActiveTrue();
}
