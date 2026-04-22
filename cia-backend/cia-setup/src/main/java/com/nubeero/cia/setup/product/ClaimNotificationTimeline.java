package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "claim_notification_timelines")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClaimNotificationTimeline extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    @Column(name = "notification_days", nullable = false)
    private int notificationDays;
}
