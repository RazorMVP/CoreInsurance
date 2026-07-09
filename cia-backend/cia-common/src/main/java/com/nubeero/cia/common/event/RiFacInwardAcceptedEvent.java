package com.nubeero.cia.common.event;

import java.math.BigDecimal;
import java.util.UUID;

/** Published when an inward FAC cover is accepted (create/renew) or extended
 *  (delta amounts). cia-finance listens → DebitNote receivable + GL posting. */
public record RiFacInwardAcceptedEvent(
        UUID facInwardId,
        String facInwardReference,
        UUID cedingCompanyId,
        String cedingCompanyName,
        UUID classOfBusinessId,
        BigDecimal grossPremium,
        BigDecimal commissionAmount,
        BigDecimal netPremium,
        String currencyCode
) {}
