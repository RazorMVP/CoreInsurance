package com.nubeero.cia.finance.gl;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link JournalEntry}.
 *
 * <p>Slice 1.4 (gateway) exposes only the finders strictly needed by
 * {@link JournalEntryService} and the read endpoint:
 * <ul>
 *   <li>{@link #findByIdAndDeletedAtIsNull(UUID)} — load by primary key,
 *       skip soft-deleted (none should exist in steady state but the
 *       BaseEntity contract requires the guard).</li>
 *   <li>{@link #findBySourceModuleAndSourceEventTypeAndSourceReference} —
 *       idempotency check from Slice 1.5's sub-ledger listeners; the DB
 *       UNIQUE makes it advisory rather than load-bearing, but reading
 *       before writing surfaces the conflict as a clean {@code Optional}
 *       instead of a wrapped {@code DataIntegrityViolationException}.</li>
 * </ul>
 *
 * <p>Later slices add reporting finders (by period, by account, paged JE
 * inquiry) — kept out of this slice to avoid a finder graveyard.
 */
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    Optional<JournalEntry> findByIdAndDeletedAtIsNull(UUID id);

    Optional<JournalEntry> findBySourceModuleAndSourceEventTypeAndSourceReference(
        String sourceModule, String sourceEventType, String sourceReference);
}
