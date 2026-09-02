package com.nubeero.cia.partner.usage;

import java.time.LocalDate;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerUsageFlushActivitiesImpl implements PartnerUsageFlushActivities {

    private final PartnerUsageRollupFlushService flushService;

    @Override
    public void flushYesterday() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        int flushed = flushService.flushDate(yesterday);
        log.info("PartnerUsageFlush: flushed {} (tenant, clientId) rollups for {}", flushed, yesterday);
    }
}
