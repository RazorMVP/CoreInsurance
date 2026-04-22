package com.nubeero.cia.setup.org;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RelationshipManagerRepository extends JpaRepository<RelationshipManager, UUID> {

    Page<RelationshipManager> findAllByDeletedAtIsNull(Pageable pageable);

    List<RelationshipManager> findAllByBranchIdAndDeletedAtIsNull(UUID branchId);
}
