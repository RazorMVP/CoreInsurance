package com.nubeero.cia.finance.gl;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link JournalEntry}.
 *
 * <p>Slice 1.4 (gateway) exposed the original finders; Slice 1.6 adds the
 * {@link #countByPeriodIdInAndDeletedAtIsNull(Collection)} predicate so
 * {@code FiscalYearService.delete} can refuse to wipe a fiscal year that
 * has any journal-entry activity through its child periods (d11 — GL is
 * immutable history).
 *
 * <p>Slice F5.4 (frontend browser) adds {@link #search(LocalDate, LocalDate,
 * UUID, String, JournalEntryStatus, String, UUID, Pageable)} — a
 * JPQL-driven multi-predicate list with optional filters. {@code DISTINCT}
 * is required because filtering by {@code accountCode} or
 * {@code classOfBusinessId} joins to {@code journal_entry_line} and a
 * single JE may have multiple matching lines.
 */
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    Optional<JournalEntry> findByIdAndDeletedAtIsNull(UUID id);

    Optional<JournalEntry> findBySourceModuleAndSourceEventTypeAndSourceReference(
        String sourceModule, String sourceEventType, String sourceReference);

    long countByPeriodIdInAndDeletedAtIsNull(Collection<UUID> periodIds);

    /**
     * Multi-predicate JE list for the Phase 5 frontend browser. All filters
     * are optional — pass {@code null} to skip. Sorting honoured via the
     * supplied {@link Pageable}. Soft-deleted rows are excluded.
     */
    @Query("""
        SELECT DISTINCT je FROM JournalEntry je
        LEFT JOIN je.lines line
        WHERE je.deletedAt IS NULL
          AND (:businessFrom IS NULL OR je.businessDate >= :businessFrom)
          AND (:businessTo   IS NULL OR je.businessDate <= :businessTo)
          AND (:periodId     IS NULL OR je.periodId = :periodId)
          AND (:sourceModule IS NULL OR je.sourceModule = :sourceModule)
          AND (:status       IS NULL OR je.status = :status)
          AND (:accountCode  IS NULL OR line.account.code = :accountCode)
          AND (:classOfBusinessId IS NULL OR line.classOfBusinessId = :classOfBusinessId)
        """)
    Page<JournalEntry> search(
        @Param("businessFrom")      LocalDate businessFrom,
        @Param("businessTo")        LocalDate businessTo,
        @Param("periodId")          UUID periodId,
        @Param("sourceModule")      String sourceModule,
        @Param("status")            JournalEntryStatus status,
        @Param("accountCode")       String accountCode,
        @Param("classOfBusinessId") UUID classOfBusinessId,
        Pageable pageable
    );
}
