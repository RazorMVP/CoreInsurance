package com.nubeero.cia.setup.customer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface CustomerNumberFormatRepository extends JpaRepository<CustomerNumberFormat, UUID> {

    Optional<CustomerNumberFormat> findFirstByDeletedAtIsNull();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM CustomerNumberFormat f WHERE f.deletedAt IS NULL")
    Optional<CustomerNumberFormat> findForUpdate();
}
