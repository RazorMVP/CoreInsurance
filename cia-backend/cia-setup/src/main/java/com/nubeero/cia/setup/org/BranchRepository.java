package com.nubeero.cia.setup.org;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BranchRepository extends JpaRepository<Branch, UUID> {
    Optional<Branch> findByCodeAndDeletedAtIsNull(String code);
    Page<Branch> findAllByDeletedAtIsNull(Pageable pageable);
    List<Branch> findAllBySbuIdAndDeletedAtIsNull(UUID sbuId);
}
