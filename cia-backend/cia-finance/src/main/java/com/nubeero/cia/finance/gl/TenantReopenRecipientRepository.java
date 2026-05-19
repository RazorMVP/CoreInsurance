package com.nubeero.cia.finance.gl;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TenantReopenRecipientRepository extends JpaRepository<TenantReopenRecipient, UUID> {

    /** Active, non-soft-deleted recipients — what the listener fans out to. */
    List<TenantReopenRecipient> findAllByActiveTrueAndDeletedAtIsNullOrderByRecipientAsc();
}
