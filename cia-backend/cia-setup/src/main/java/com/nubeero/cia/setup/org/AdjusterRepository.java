package com.nubeero.cia.setup.org;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AdjusterRepository extends JpaRepository<Adjuster, UUID> {

    Page<Adjuster> findAllByDeletedAtIsNull(Pageable pageable);
}
