package com.nubeero.cia.finance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface CreditNoteRepository extends JpaRepository<CreditNote, UUID> {

    Optional<CreditNote> findByIdAndDeletedAtIsNull(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CreditNote c where c.id = :id and c.deletedAt is null")
    Optional<CreditNote> findByIdAndDeletedAtIsNullForUpdate(@Param("id") UUID id);

    Page<CreditNote> findAllByDeletedAtIsNull(Pageable pageable);

    Page<CreditNote> findAllByStatusAndDeletedAtIsNull(CreditNoteStatus status, Pageable pageable);

    Page<CreditNote> findAllByEntityIdAndDeletedAtIsNull(UUID entityId, Pageable pageable);

    Optional<CreditNote> findByEntityIdAndEntityTypeAndDeletedAtIsNull(UUID entityId,
                                                                        FinanceEntityType entityType);
}
