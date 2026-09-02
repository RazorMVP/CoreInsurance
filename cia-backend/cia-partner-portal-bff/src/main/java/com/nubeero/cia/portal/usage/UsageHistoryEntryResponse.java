package com.nubeero.cia.portal.usage;

import com.nubeero.cia.partner.usage.PartnerRequestDaily;
import java.time.LocalDate;

/** One durably-flushed day from {@code partner_request_daily}, most-recent-first. */
public record UsageHistoryEntryResponse(
        LocalDate date, long total, long success, long clientError, long serverError) {

    static UsageHistoryEntryResponse from(PartnerRequestDaily row) {
        return new UsageHistoryEntryResponse(
                row.getUsageDate(), row.getTotal(), row.getSuccess(), row.getClientError(), row.getServerError());
    }
}
