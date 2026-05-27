package com.nubeero.cia.finance.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PdfDownloadLogRepository extends JpaRepository<PdfDownloadLog, UUID> {

    List<PdfDownloadLog> findByUserIdAndDownloadedAtAfterOrderByDownloadedAtDesc(
            String userId, Instant from, Pageable pageable);

    @Modifying
    @Transactional
    @Query("delete from PdfDownloadLog p where p.downloadedAt < :cutoff")
    int deleteByDownloadedAtBefore(Instant cutoff);
}
