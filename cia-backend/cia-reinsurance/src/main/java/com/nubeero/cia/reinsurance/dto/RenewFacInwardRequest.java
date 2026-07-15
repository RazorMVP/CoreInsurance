package com.nubeero.cia.reinsurance.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Renew from a source cover: new term dates; premium terms carry over from
 *  the source unless overridden (v1 carries over — no overrides). */
public record RenewFacInwardRequest(
        @NotNull LocalDate coverFrom,
        @NotNull LocalDate coverTo
) {}
