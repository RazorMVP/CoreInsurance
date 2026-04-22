package com.nubeero.cia.quotation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "quote_counters")
@Getter
@Setter
@NoArgsConstructor
public class QuoteCounter {

    @Id
    @Column(nullable = false)
    private int year;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    public QuoteCounter(int year) {
        this.year = year;
        this.lastSequence = 0;
    }
}
