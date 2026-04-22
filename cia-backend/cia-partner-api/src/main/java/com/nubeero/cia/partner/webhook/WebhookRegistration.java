package com.nubeero.cia.partner.webhook;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "webhook_registrations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookRegistration extends BaseEntity {

    @Column(name = "partner_app_id", nullable = false)
    private UUID partnerAppId;

    @Column(name = "target_url", nullable = false, length = 500)
    private String targetUrl;

    /** HMAC-SHA256 signing secret for this registration */
    @Column(nullable = false, length = 200)
    private String secret;

    /** Comma-separated list of subscribed event types */
    @Column(name = "event_types", nullable = false, columnDefinition = "TEXT")
    private String eventTypes;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
