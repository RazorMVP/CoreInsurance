package com.nubeero.cia.setup.org;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReinsuranceCompanyRepository extends JpaRepository<ReinsuranceCompany, UUID> {

    Page<ReinsuranceCompany> findAllByDeletedAtIsNull(Pageable pageable);

    Optional<ReinsuranceCompany> findByIdAndDeletedAtIsNull(UUID id);
}
