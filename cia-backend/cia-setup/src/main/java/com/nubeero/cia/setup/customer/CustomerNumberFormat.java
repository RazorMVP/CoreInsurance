package com.nubeero.cia.setup.customer;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "customer_number_format")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerNumberFormat extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String prefix;

    @Column(name = "include_year", nullable = false)
    private boolean includeYear;

    @Column(name = "include_type", nullable = false)
    private boolean includeType;

    @Column(name = "sequence_length", nullable = false)
    private int sequenceLength;

    /** Shared counter used when includeType = false. */
    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    /** Per-type counter used when includeType = true. */
    @Column(name = "last_sequence_individual", nullable = false)
    private long lastSequenceIndividual;

    @Column(name = "last_sequence_corporate", nullable = false)
    private long lastSequenceCorporate;
}
