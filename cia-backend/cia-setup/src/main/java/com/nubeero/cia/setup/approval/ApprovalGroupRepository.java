package com.nubeero.cia.setup.approval;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApprovalGroupRepository extends JpaRepository<ApprovalGroup, UUID> {
    Page<ApprovalGroup> findAllByDeletedAtIsNull(Pageable pageable);
    List<ApprovalGroup> findAllByEntityTypeAndDeletedAtIsNull(String entityType);
}
