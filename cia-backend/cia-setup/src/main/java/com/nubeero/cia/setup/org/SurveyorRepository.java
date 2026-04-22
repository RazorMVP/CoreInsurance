package com.nubeero.cia.setup.org;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SurveyorRepository extends JpaRepository<Surveyor, UUID> {

    Page<Surveyor> findAllByDeletedAtIsNull(Pageable pageable);
}
