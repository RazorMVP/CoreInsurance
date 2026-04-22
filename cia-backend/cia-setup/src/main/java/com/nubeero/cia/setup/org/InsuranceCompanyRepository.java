package com.nubeero.cia.setup.org;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InsuranceCompanyRepository extends JpaRepository<InsuranceCompany, UUID> {

    Page<InsuranceCompany> findAllByDeletedAtIsNull(Pageable pageable);
}
