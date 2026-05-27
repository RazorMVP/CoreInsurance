package com.nubeero.cia.setup.notification;

import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantNotificationTemplateRepository
        extends JpaRepository<TenantNotificationTemplate, UUID> {

    Optional<TenantNotificationTemplate> findByTemplateTypeAndChannel(
            NotificationTemplateType templateType, NotificationChannel channel);

    List<TenantNotificationTemplate> findAllByOrderByTemplateTypeAscChannelAsc();

    boolean existsByTemplateTypeAndChannel(
            NotificationTemplateType templateType, NotificationChannel channel);
}
