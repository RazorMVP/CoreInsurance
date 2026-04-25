package com.nubeero.cia.dashboard;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RenewalDayDto {
    String date;    // "2026-04-25"
    String label;   // "Mon", "Tue", etc.
    long count;
}
