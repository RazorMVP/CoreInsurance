package com.nubeero.cia.finance;

import java.io.Serializable;
import java.util.Objects;

public class FinanceCounterKey implements Serializable {

    private String counterType;
    private int year;

    public FinanceCounterKey() {}

    public FinanceCounterKey(String counterType, int year) {
        this.counterType = counterType;
        this.year = year;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FinanceCounterKey that)) return false;
        return year == that.year && Objects.equals(counterType, that.counterType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(counterType, year);
    }
}
