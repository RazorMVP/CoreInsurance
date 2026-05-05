package com.nubeero.cia.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PolicySurveyRepository extends JpaRepository<PolicySurvey, UUID> {

    Optional<PolicySurvey> findByPolicyIdAndDeletedAtIsNull(UUID policyId);
}
