package com.nubeero.cia.partner.webhook;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_delivery_logs")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookDeliveryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "webhook_registration_id", nullable = false)
    private UUID webhookRegistrationId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    @Builder.Default
    private int attempt = 1;

    @CreationTimestamp
    @Column(name = "delivered_at", nullable = false, updatable = false)
    private Instant deliveredAt;
}
