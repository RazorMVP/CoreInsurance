package com.nubeero.cia.setup.loss;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "cause_of_loss")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CauseOfLoss extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nature_of_loss_id")
    private NatureOfLoss natureOfLoss;
}
