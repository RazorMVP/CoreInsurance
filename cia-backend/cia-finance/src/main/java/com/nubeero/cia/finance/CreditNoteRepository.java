package com.nubeero.cia.finance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CreditNoteRepository extends JpaRepository<CreditNote, UUID> {

    Optional<CreditNote> findByIdAndDeletedAtIsNull(UUID id);

    Page<CreditNote> findAllByDeletedAtIsNull(Pageable pageable);

    Page<CreditNote> findAllByStatusAndDeletedAtIsNull(CreditNoteStatus status, Pageable pageable);

    Page<CreditNote> findAllByEntityIdAndDeletedAtIsNull(UUID entityId, Pageable pageable);

    Optional<CreditNote> findByEntityIdAndEntityTypeAndDeletedAtIsNull(UUID entityId,
                                                                        FinanceEntityType entityType);
}
