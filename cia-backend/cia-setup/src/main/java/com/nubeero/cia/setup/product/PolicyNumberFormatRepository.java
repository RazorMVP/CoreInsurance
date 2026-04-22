package com.nubeero.cia.setup.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface PolicyNumberFormatRepository extends JpaRepository<PolicyNumberFormat, UUID> {
    Optional<PolicyNumberFormat> findByProductIdAndDeletedAtIsNull(UUID productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM PolicyNumberFormat f WHERE f.product.id = :productId AND f.deletedAt IS NULL")
    Optional<PolicyNumberFormat> findByProductIdForUpdate(UUID productId);
}
