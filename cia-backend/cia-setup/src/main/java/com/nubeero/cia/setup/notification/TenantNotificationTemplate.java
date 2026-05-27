package com.nubeero.cia.setup.notification;

import com.nubeero.cia.common.entity.BaseEntity;
import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tenant_notification_template")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantNotificationTemplate extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "template_type", nullable = false, length = 40)
    private NotificationTemplateType templateType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "subject_template", columnDefinition = "TEXT")
    private String subjectTemplate;

    @Column(name = "body_template", columnDefinition = "TEXT")
    private String bodyTemplate;
}
