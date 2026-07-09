package com.nubeero.cia.reinsurance.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Extend the current cover_to to newCoverTo (must be after the current cover_to). */
public record ExtendFacInwardRequest(
        @NotNull LocalDate newCoverTo
) {}
