package com.nubeero.cia.finance.gl;

import org.springframework.data.jpa.repository.JpaRepository;

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
 * immutable history). Reusing a Spring Data method-name finder keeps the
 * query free of hand-written JPQL.
 */
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    Optional<JournalEntry> findByIdAndDeletedAtIsNull(UUID id);

    Optional<JournalEntry> findBySourceModuleAndSourceEventTypeAndSourceReference(
        String sourceModule, String sourceEventType, String sourceReference);

    long countByPeriodIdInAndDeletedAtIsNull(Collection<UUID> periodIds);
}
