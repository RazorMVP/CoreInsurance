package com.nubeero.cia.setup.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClassOfBusinessRepository extends JpaRepository<ClassOfBusiness, UUID> {
    Optional<ClassOfBusiness> findByCodeAndDeletedAtIsNull(String code);
    Page<ClassOfBusiness> findAllByDeletedAtIsNull(Pageable pageable);
    List<ClassOfBusiness> findAllByDeletedAtIsNull();
    boolean existsByCodeAndDeletedAtIsNull(String code);
}
