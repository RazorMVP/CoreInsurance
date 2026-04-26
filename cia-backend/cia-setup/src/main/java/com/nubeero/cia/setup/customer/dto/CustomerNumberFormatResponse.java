package com.nubeero.cia.setup.customer.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class CustomerNumberFormatResponse {
    private UUID id;
    private String prefix;
    private boolean includeYear;
    private boolean includeType;
    private int sequenceLength;
    private long lastSequence;
    private long lastSequenceIndividual;
    private long lastSequenceCorporate;
    private Instant createdAt;
    private Instant updatedAt;
}
