package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "policy_number_formats")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyNumberFormat extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    @Column(nullable = false, length = 20)
    private String prefix;

    @Column(name = "include_year", nullable = false)
    private boolean includeYear;

    @Column(name = "include_class_code", nullable = false)
    private boolean includeClassCode;

    @Column(name = "sequence_length", nullable = false)
    private int sequenceLength;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;
}
