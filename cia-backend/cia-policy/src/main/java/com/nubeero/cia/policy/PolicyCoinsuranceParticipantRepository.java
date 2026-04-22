package com.nubeero.cia.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PolicyCoinsuranceParticipantRepository extends JpaRepository<PolicyCoinsuranceParticipant, UUID> {

    List<PolicyCoinsuranceParticipant> findAllByPolicyIdAndDeletedAtIsNull(UUID policyId);
}
