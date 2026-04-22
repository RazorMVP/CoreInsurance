package com.nubeero.cia.reinsurance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ri_fac_counters")
@Getter
@Setter
public class RiFacCounter {

    @Id
    @Column(name = "year", nullable = false)
    private int year;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;
}
