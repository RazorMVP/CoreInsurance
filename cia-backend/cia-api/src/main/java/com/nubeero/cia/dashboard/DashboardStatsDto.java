package com.nubeero.cia.dashboard;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class DashboardStatsDto {
    long activePolicies;
    long openClaims;
    long pendingApprovals;
    BigDecimal premiumsMtd;
    BigDecimal claimsReserveTotal;
    long renewalsDue30Days;
    BigDecimal outstandingPremium;
    BigDecimal riUtilisationPct;
}
