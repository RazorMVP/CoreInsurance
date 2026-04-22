package com.nubeero.cia.claims;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "claim_counters")
public class ClaimCounter {

    @Id
    @Column(name = "year")
    private int year;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence = 0;

    public ClaimCounter() {}

    public ClaimCounter(int year) {
        this.year = year;
    }

    public int getYear() { return year; }
    public long getLastSequence() { return lastSequence; }
    public void setLastSequence(long lastSequence) { this.lastSequence = lastSequence; }
}
