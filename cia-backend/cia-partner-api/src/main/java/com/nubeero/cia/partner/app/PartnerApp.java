package com.nubeero.cia.partner.app;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "partner_apps")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerApp extends BaseEntity {

    @Column(name = "client_id", nullable = false, unique = true, length = 100)
    private String clientId;

    @Column(name = "app_name", nullable = false, length = 200)
    private String appName;

    @Column(name = "contact_email", nullable = false, length = 200)
    private String contactEmail;

    /** Space-separated OAuth2 scopes granted to this partner, e.g. "products:read quotes:create" */
    @Column(nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String scopes = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PartnerPlan plan = PartnerPlan.STARTER;

    /** Requests per minute allowed for this app */
    @Column(name = "rate_limit_rpm", nullable = false)
    @Builder.Default
    private int rateLimitRpm = 60;

    /** Optional CIDR allowlist, comma-separated; null = unrestricted */
    @Column(name = "allowed_ips", columnDefinition = "TEXT")
    private String allowedIps;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
