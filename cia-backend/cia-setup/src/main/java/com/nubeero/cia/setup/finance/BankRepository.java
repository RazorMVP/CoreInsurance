package com.nubeero.cia.setup.finance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BankRepository extends JpaRepository<Bank, UUID> {
    Optional<Bank> findByCodeAndDeletedAtIsNull(String code);
    Page<Bank> findAllByDeletedAtIsNull(Pageable pageable);
}
