package com.nubeero.cia.setup.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    Optional<Product> findByCodeAndDeletedAtIsNull(String code);
    Page<Product> findAllByDeletedAtIsNull(Pageable pageable);
    List<Product> findAllByClassOfBusinessIdAndDeletedAtIsNull(UUID classOfBusinessId);
    List<Product> findAllByActiveAndDeletedAtIsNull(boolean active);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(UUID id);
}
