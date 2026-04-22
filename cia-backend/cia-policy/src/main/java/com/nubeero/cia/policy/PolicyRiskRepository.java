package com.nubeero.cia.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PolicyRiskRepository extends JpaRepository<PolicyRisk, UUID> {

    List<PolicyRisk> findAllByPolicyIdAndDeletedAtIsNull(UUID policyId);
}
