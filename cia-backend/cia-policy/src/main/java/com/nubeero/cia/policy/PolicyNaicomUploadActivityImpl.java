package com.nubeero.cia.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.integrations.naicom.NaicomService;
import com.nubeero.cia.integrations.naicom.NaicomUploadRequest;
import com.nubeero.cia.integrations.naicom.NaicomUploadResult;
import com.nubeero.cia.workflow.naicom.NaicomUploadActivity;
import io.temporal.activity.Activity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyNaicomUploadActivityImpl implements NaicomUploadActivity {

    private final PolicyRepository policyRepository;
    private final NaicomService naicomService;
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
    public NaicomUploadResult uploadToNaicom(String policyId, String tenantId, String policyJson) {
        Policy policy = findPolicy(policyId);
        return naicomService.uploadPolicy(NaicomUploadRequest.builder()
                .policyNumber(policy.getPolicyNumber())
                .tenantId(tenantId)
                .policyJson(policyJson)
                .build());
    }

    @Override
    public void updatePolicyCertificate(String policyId, String tenantId, String naicomUid) {
        Policy policy = findPolicy(policyId);
        policy.setNaicomUid(naicomUid);
        policy.setNaicomUploadedAt(Instant.now());
        policyRepository.save(policy);
        log.info("NAICOM UID {} applied to policy {}", naicomUid, policyId);
    }

    private Policy findPolicy(String policyId) {
        return policyRepository.findById(UUID.fromString(policyId))
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", UUID.fromString(policyId)));
    }
}
