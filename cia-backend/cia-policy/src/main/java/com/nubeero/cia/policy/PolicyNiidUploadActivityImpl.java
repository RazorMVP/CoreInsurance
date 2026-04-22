package com.nubeero.cia.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.integrations.niid.NiidService;
import com.nubeero.cia.integrations.niid.NiidUploadRequest;
import com.nubeero.cia.integrations.niid.NiidUploadResult;
import com.nubeero.cia.workflow.niid.NiidUploadActivity;
import io.temporal.activity.Activity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyNiidUploadActivityImpl implements NiidUploadActivity {

    private final PolicyRepository policyRepository;
    private final NiidService niidService;
    private final ObjectMapper objectMapper;

    @Override
    public String fetchPolicyPayload(String policyId, String tenantId) {
        Policy policy = findPolicy(policyId);
        try {
            return objectMapper.writeValueAsString(policy);
        } catch (Exception e) {
            throw Activity.wrap(e);
        }
    }

    @Override
    public NiidUploadResult uploadToNiid(String policyId, String tenantId, String policyJson) {
        Policy policy = findPolicy(policyId);
        String vehicleReg = policy.getRisks().stream()
                .filter(r -> r.getVehicleRegNumber() != null)
                .map(PolicyRisk::getVehicleRegNumber)
                .findFirst().orElse(null);
        return niidService.uploadPolicy(NiidUploadRequest.builder()
                .policyNumber(policy.getPolicyNumber())
                .vehicleRegNumber(vehicleReg)
                .tenantId(tenantId)
                .policyJson(policyJson)
                .build());
    }

    @Override
    public void updatePolicyNiidRef(String policyId, String tenantId, String niidRef) {
        Policy policy = findPolicy(policyId);
        policy.setNiidRef(niidRef);
        policy.setNiidUploadedAt(Instant.now());
        policyRepository.save(policy);
        log.info("NIID ref {} applied to policy {}", niidRef, policyId);
    }

    private Policy findPolicy(String policyId) {
        return policyRepository.findById(UUID.fromString(policyId))
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", UUID.fromString(policyId)));
    }
}
