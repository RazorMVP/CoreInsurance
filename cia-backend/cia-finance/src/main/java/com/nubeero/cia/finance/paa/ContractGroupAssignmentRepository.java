package com.nubeero.cia.finance.paa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContractGroupAssignmentRepository extends JpaRepository<ContractGroupAssignment, UUID> {

    Optional<ContractGroupAssignment> findByContractTypeAndContractIdAndDeletedAtIsNull(
        ContractType contractType, UUID contractId);

    boolean existsByContractTypeAndContractIdAndDeletedAtIsNull(ContractType contractType, UUID contractId);

    /**
     * All assignments under a given group — used by downstream IFRS-17
     * measurement (LRC/LIC engines) to enumerate the contracts feeding each
     * (group, period) roll-forward row.
     */
    List<ContractGroupAssignment> findByGroupIdAndDeletedAtIsNullOrderByAssignedAtAsc(UUID groupId);
}
