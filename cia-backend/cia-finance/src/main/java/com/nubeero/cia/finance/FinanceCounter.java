package com.nubeero.cia.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "finance_counters")
@IdClass(FinanceCounterKey.class)
public class FinanceCounter {

    @Id
    @Column(name = "counter_type")
    private String counterType;

    @Id
    @Column(name = "year")
    private int year;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence = 0;

    public FinanceCounter() {}

    public FinanceCounter(String counterType, int year) {
        this.counterType = counterType;
        this.year = year;
    }

    public String getCounterType() { return counterType; }
    public int getYear() { return year; }
    public long getLastSequence() { return lastSequence; }
    public void setLastSequence(long lastSequence) { this.lastSequence = lastSequence; }
}
