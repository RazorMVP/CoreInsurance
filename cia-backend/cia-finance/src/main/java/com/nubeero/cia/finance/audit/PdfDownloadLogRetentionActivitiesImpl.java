package com.nubeero.cia.finance.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PdfDownloadLogRetentionActivitiesImpl
        implements PdfDownloadLogRetentionActivities {

    private final PdfDownloadLogRepository repository;

    @Override
    @Transactional
    public void purgeOlderThan30Days() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        int deleted = repository.deleteByDownloadedAtBefore(cutoff);
        log.info("PdfDownloadLogRetention: purged {} rows older than {}", deleted, cutoff);
    }
}
