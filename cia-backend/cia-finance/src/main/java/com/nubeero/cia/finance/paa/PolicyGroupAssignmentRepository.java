package com.nubeero.cia.finance.paa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PolicyGroupAssignmentRepository extends JpaRepository<PolicyGroupAssignment, UUID> {

    Optional<PolicyGroupAssignment> findByPolicyIdAndDeletedAtIsNull(UUID policyId);

    boolean existsByPolicyIdAndDeletedAtIsNull(UUID policyId);

    /**
     * All assignments under a given group — used by downstream IFRS-17
     * measurement (LRC/LIC engines in Slice 2.3 / 2.4) to enumerate the
     * contracts feeding each (group, period) roll-forward row.
     */
    List<PolicyGroupAssignment> findByGroupIdAndDeletedAtIsNullOrderByAssignedAtAsc(UUID groupId);
}
