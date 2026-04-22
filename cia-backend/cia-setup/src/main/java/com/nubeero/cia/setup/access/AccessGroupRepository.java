package com.nubeero.cia.setup.access;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccessGroupRepository extends JpaRepository<AccessGroup, UUID> {

    Page<AccessGroup> findAllByDeletedAtIsNull(Pageable pageable);

    boolean existsByNameAndDeletedAtIsNull(String name);
}
