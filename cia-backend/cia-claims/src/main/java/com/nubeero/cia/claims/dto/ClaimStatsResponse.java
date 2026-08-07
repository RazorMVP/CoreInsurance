package com.nubeero.cia.claims.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** Dashboard aggregate for the claims list StatCards. Summed server-side over
 *  every non-deleted claim so the numbers stay correct once the list is paged
 *  (a page-local reduce would only see the current page). */
@Data
@Builder
public class ClaimStatsResponse {

    /** Claims not in a terminal state (SETTLED / WITHDRAWN). */
    private long openCount;

    /** Sum of reserve_amount across all non-deleted claims. */
    private BigDecimal totalReserve;

    /** Sum of approved_amount across all non-deleted claims. */
    private BigDecimal totalApproved;
}
