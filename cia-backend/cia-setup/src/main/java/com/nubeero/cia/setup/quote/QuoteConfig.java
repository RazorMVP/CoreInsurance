package com.nubeero.cia.setup.quote;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "quote_config")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class QuoteConfig extends BaseEntity {

    @Column(name = "validity_days", nullable = false)
    private int validityDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "calc_sequence", nullable = false, length = 30)
    private CalcSequence calcSequence;
}
