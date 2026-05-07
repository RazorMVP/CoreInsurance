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

public interface DebitNoteRepository extends JpaRepository<DebitNote, UUID> {

    Optional<DebitNote> findByIdAndDeletedAtIsNull(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DebitNote d where d.id = :id and d.deletedAt is null")
    Optional<DebitNote> findByIdAndDeletedAtIsNullForUpdate(@Param("id") UUID id);

    Page<DebitNote> findAllByDeletedAtIsNull(Pageable pageable);

    Page<DebitNote> findAllByStatusAndDeletedAtIsNull(DebitNoteStatus status, Pageable pageable);

    Page<DebitNote> findAllByCustomerIdAndDeletedAtIsNull(UUID customerId, Pageable pageable);

    Page<DebitNote> findAllByEntityIdAndDeletedAtIsNull(UUID entityId, Pageable pageable);

    Optional<DebitNote> findByEntityIdAndEntityTypeAndDeletedAtIsNull(UUID entityId,
                                                                       FinanceEntityType entityType);
}
