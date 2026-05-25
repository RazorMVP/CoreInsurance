package com.nubeero.cia.finance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByIdAndDeletedAtIsNull(UUID id);

    Page<Payment> findAllByCreditNote_IdAndDeletedAtIsNull(UUID creditNoteId, Pageable pageable);

    List<Payment> findAllByCreditNote_IdAndStatusAndDeletedAtIsNull(UUID creditNoteId,
                                                                     TransactionStatus status);
}
