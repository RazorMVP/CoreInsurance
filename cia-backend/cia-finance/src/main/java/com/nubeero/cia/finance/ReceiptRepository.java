package com.nubeero.cia.finance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID>, JpaSpecificationExecutor<Receipt> {

    Optional<Receipt> findByIdAndDeletedAtIsNull(UUID id);

    Page<Receipt> findAllByDebitNote_IdAndDeletedAtIsNull(UUID debitNoteId, Pageable pageable);

    List<Receipt> findAllByDebitNote_IdAndStatusAndDeletedAtIsNull(UUID debitNoteId,
                                                                    TransactionStatus status);
}
